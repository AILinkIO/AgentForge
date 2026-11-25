package io.ailink.agentforge.bitcask.cypher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import io.ailink.agentforge.bitcask.BatchOp;
import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Condition;
import io.ailink.agentforge.bitcask.Document;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.VersionedValue;
import io.ailink.agentforge.bitcask.graph.Adjacency;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.GraphKeys;
import io.ailink.agentforge.bitcask.graph.GraphModel;

/**
 * Cypher 执行器的存储访问（clj {@code cypher.store}）。两种实现同一个接口：
 * <ul>
 * <li>{@link Direct}：只读语句直接读库（图层的读原语，每次读都是当下的状态）。</li>
 * <li>{@link Tx}：写语句的语句事务（乐观并发）：邻接表经图层写会话（读头带 LSN、改动攒在内存）；顶点文档、标签索引、id 计数器、
 *     外置属性经 KV 读写缓冲（读时记下版本 / 不存在，写进缓冲）。提交时两边合成一条带条件的原子批：改过的键带读到的版本，只读过的键出
 *     check——期间别的写者改了任何一个读过的键 ⇒ 整批零副作用、整条语句重跑。读都看得到本语句之前的写。</li>
 * </ul>
 */
interface Store {
    int CHUNK = 256;

    /** 底层库句柄。 */
    Bitcask db();

    /** 顶点存在（n 文档或邻接表头）。 */
    boolean exists(long vid);

    /** 顶点文档或 null（ord 不保证）。 */
    GraphModel.Doc doc(long vid);

    /** dir 0 出 / 1 入，etype null = 全部 ⇒ 边 + 对端。 */
    List<RelHop> edges(long vid, int dir, Long etype);

    /** 边的属性字节；边不存在 ⇒ null。 */
    byte[] edgeProps(long src, long etype, long dst, long rank);

    /** 带标签的顶点 id（无符号升序，惰性）。 */
    Iterable<Long> labelVids(String label);

    /** 全部顶点 id（n 文档 ∪ 邻接表头，无符号升序，惰性）。 */
    Iterable<Long> allVids();

    static Direct direct(Bitcask db) {
        return new Direct(db);
    }

    static Tx tx(Bitcask db) {
        return new Tx(db);
    }

    // ═══ 直读 ═══

    /** 分批取的有序 id 惰性序列：fetch(after) ⇒ 至多 CHUNK 个升序 id。 */
    private static Iterable<Long> chunked(Function<Long, List<Long>> fetch) {
        return () -> new Iterator<>() {
            List<Long> buf = null;
            int i = 0;
            boolean done = false;

            private void fill() {
                if (done || (buf != null && i < buf.size())) return;
                if (buf != null && buf.size() < CHUNK) {
                    done = true;
                    return;
                }
                Long after = buf == null ? null : buf.getLast();
                buf = fetch.apply(after);
                i = 0;
                if (buf.isEmpty()) done = true;
            }

            @Override
            public boolean hasNext() {
                fill();
                return !done;
            }

            @Override
            public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                return buf.get(i++);
            }
        };
    }

    /** 家族 n / a 里 after 之后的至多 CHUNK 个顶点 id（a 家族的分块键跳过）。 */
    private static List<Long> familyVids(Bitcask db, String fam, Function<byte[], Long> parse, Long after) {
        List<Long> out = new ArrayList<>();
        if (after != null && after == GraphModel.VID_MAX) return out;
        byte[] lo = after == null ? GraphKeys.familyBytes(fam) : GraphKeys.familyVid(fam, after + 1);
        db.forEachRange(RangeOptions.between(lo, GraphKeys.successor(GraphKeys.familyBytes(fam))).batch(CHUNK), e -> {
            Long v = parse.apply(e.key());
            if (v != null) out.add(v);
            return out.size() < CHUNK;
        });
        return out;
    }

    /** 两条无符号升序 id 序列的并集（去重，惰性）。 */
    private static Iterable<Long> mergeSorted(Iterable<Long> a, Iterable<Long> b) {
        return () -> new Iterator<>() {
            final Iterator<Long> ia = a.iterator(), ib = b.iterator();
            Long x = null, y = null;

            @Override
            public boolean hasNext() {
                if (x == null && ia.hasNext()) x = ia.next();
                if (y == null && ib.hasNext()) y = ib.next();
                return x != null || y != null;
            }

            @Override
            public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                Long r;
                if (x == null) {
                    r = y;
                    y = null;
                } else if (y == null) {
                    r = x;
                    x = null;
                } else {
                    int c = Long.compareUnsigned(x, y);
                    if (c < 0) {
                        r = x;
                        x = null;
                    } else if (c > 0) {
                        r = y;
                        y = null;
                    } else {
                        r = x;
                        x = null;
                        y = null;
                    }
                }
                return r;
            }
        };
    }

    private static Iterable<Long> dbAllVids(Bitcask db) {
        return mergeSorted(chunked(after -> familyVids(db, "n", GraphKeys::parseNode, after)),
                chunked(after -> familyVids(db, "a", GraphKeys::parseHead, after)));
    }

    /** 只读：直接读库。 */
    final class Direct implements Store {
        private final Bitcask db;

        Direct(Bitcask db) {
            this.db = db;
        }

        @Override
        public Bitcask db() {
            return db;
        }

        @Override
        public boolean exists(long vid) {
            return GraphModel.vertexExists(db, vid);
        }

        @Override
        public GraphModel.Doc doc(long vid) {
            return GraphModel.readDoc(db, vid);
        }

        @Override
        public List<RelHop> edges(long vid, int dir, Long etype) {
            Adjacency.RowMaker<RelHop> mk = dir == 0
                    ? (t, o, r, p) -> {
                        byte[] props = p;
                        if (Adjacency.isExt(p)) {
                            byte[] v = db.get(GraphKeys.propsKey(vid, t, o, r));
                            props = v != null ? v : new byte[0];
                        }
                        return new RelHop(new VRel(vid, t, o, r, props), o);
                    }
                    : (t, o, r, p) -> new RelHop(new VRel(o, t, vid, r, VRel.UNKNOWN), o);
            return Adjacency.groupRows(db, vid, Adjacency.headGroups(db, vid, Adjacency.rawFn(dir, etype)), dir, etype, -1, mk);
        }

        @Override
        public byte[] edgeProps(long s, long t, long d, long r) {
            var e = Graph.open(db).edge(s, t, d, r);
            return e == null ? null : e.props();
        }

        @Override
        public Iterable<Long> labelVids(String label) {
            return chunked(after -> GraphModel.labelVids(db, label, CHUNK, after));
        }

        @Override
        public Iterable<Long> allVids() {
            return dbAllVids(db);
        }
    }

    // ═══ 语句事务 ═══

    /** KV 缓冲的一项：读到的版本（null = 没读过）、当前值、写法（null = 只读）。 */
    final class KvEntry {
        final byte[] key;
        Condition cond;
        Object val;      // byte[]（raw）或 GraphModel.Doc（顶点文档）
        String write;    // null | put | put-doc | delete

        KvEntry(byte[] key) {
            this.key = key;
        }
    }

    final class Tx implements Store {
        final Bitcask db;
        final Adjacency.Session sess;
        final Map<ByteBuffer, KvEntry> kv = new LinkedHashMap<>();
        final Map<String, Map<Long, String>> labels = new HashMap<>();

        Tx(Bitcask db) {
            this.db = db;
            this.sess = Graph.newSession(db);
        }

        private static ByteBuffer bkey(Object k) {
            return ByteBuffer.wrap(k instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : (byte[]) k);
        }

        private void check(long vid) {
            sess.checkHead(vid);
        }

        /** 事务里读一个键的原始字节（登记版本）；不存在 ⇒ null。 */
        byte[] readRaw(Object k) {
            ByteBuffer kk = bkey(k);
            KvEntry e = kv.get(kk);
            if (e != null) return (byte[]) e.val;
            VersionedValue v = db.getWithOrd(kk.array());
            e = new KvEntry(kk.array());
            e.cond = v != null ? Condition.ord(v.ord()) : Condition.absent();
            e.val = v != null ? v.value() : null;
            kv.put(kk, e);
            return (byte[]) e.val;
        }

        private GraphModel.Doc readDoc(long vid) {
            ByteBuffer kk = bkey(GraphKeys.nodeBytes(vid));
            KvEntry e = kv.get(kk);
            if (e != null) return (GraphModel.Doc) e.val;
            GraphModel.Doc d = GraphModel.readDoc(db, vid);
            e = new KvEntry(kk.array());
            e.cond = d != null ? Condition.ord(d.ord()) : Condition.absent();
            e.val = d;
            kv.put(kk, e);
            return d;
        }

        /** 事务里写一个键：op ∈ put（byte[]）| put-doc（{@link GraphModel.Doc}）| delete（v 忽略）。读过的键提交时带读到的版本。 */
        void write(String op, Object k, Object v) {
            ByteBuffer kk = bkey(k);
            KvEntry e = kv.computeIfAbsent(kk, x -> new KvEntry(kk.array()));
            e.write = op;
            e.val = "delete".equals(op) ? null : v;
            GraphKeys.LabelKey lk = GraphKeys.parseLabelKey(kk.array());
            if (lk != null) labels.computeIfAbsent(lk.label(), x -> new LinkedHashMap<>()).put(lk.vid(), "delete".equals(op) ? "delete" : "put");
        }

        private boolean headExists(long vid) {
            boolean e = sess.headExists(vid);
            check(vid);
            return e;
        }

        private byte[] extProps(long s, long et, long d, long r) {
            byte[] v = readRaw(GraphKeys.propsKey(s, et, d, r));
            return v != null ? v : new byte[0];
        }

        @Override
        public Bitcask db() {
            return db;
        }

        @Override
        public boolean exists(long vid) {
            return readDoc(vid) != null || headExists(vid);
        }

        @Override
        public GraphModel.Doc doc(long vid) {
            return readDoc(vid);
        }

        @Override
        public List<RelHop> edges(long vid, int dir, Long etype) {
            var es = sess.allEntries(vid);
            check(vid);
            List<RelHop> out = new ArrayList<>();
            for (var e : es) {
                if (e.dir() != dir || (etype != null && e.etype() != etype)) continue;
                if (e.dir() == 0) {
                    byte[] p = Adjacency.isExt(e.props()) ? extProps(vid, e.etype(), e.other(), e.rank()) : e.props();
                    out.add(new RelHop(new VRel(vid, e.etype(), e.other(), e.rank(), p), e.other()));
                } else out.add(new RelHop(new VRel(e.other(), e.etype(), vid, e.rank(), VRel.UNKNOWN), e.other()));
            }
            return out;
        }

        @Override
        public byte[] edgeProps(long s, long et, long d, long r) {
            byte[] cur = sess.entry(s, 0, et, d, r);
            check(s);
            if (cur == null) return null;
            return Adjacency.isExt(cur) ? extProps(s, et, d, r) : cur;
        }

        @Override
        public Iterable<Long> labelVids(String label) {
            TreeSet<Long> vs = new TreeSet<>(Long::compareUnsigned);
            vs.addAll(GraphModel.labelVids(db, label, -1, null));
            Map<Long, String> own = labels.get(label);
            if (own != null) own.forEach((vid, op) -> {
                if (op.equals("put")) vs.add(vid);
                else vs.remove(vid);
            });
            return new ArrayList<>(vs);
        }

        @Override
        public Iterable<Long> allVids() {
            TreeSet<Long> vs = new TreeSet<>(Long::compareUnsigned);
            for (Long v : dbAllVids(db)) vs.add(v);
            for (KvEntry e : kv.values()) {
                if ("put-doc".equals(e.write)) {
                    Long v = GraphKeys.parseNode(e.key);
                    if (v != null) vs.add(v);
                }
            }
            List<Long> out = new ArrayList<>();
            for (Long v : vs) if (exists(v)) out.add(v);
            return out;
        }

        // ── 事务里的写 ──

        /** 文档 ⇒ 模型（props 不含正文与向量）；meta 解不开 ⇒ null（不覆盖看不懂的数据）。 */
        static VModel docModel(GraphModel.Doc d) {
            Map<String, Object> m = GraphModel.safeDecodeMeta(d.meta());
            if (m == null) return null;
            return new VModel(GraphModel.labelsFromMeta(d.meta()), GraphModel.decodeTyped(m), d.text() == null ? "" : d.text(), d.vector());
        }

        /** 模型 ⇒ 写给引擎的文档。 */
        static GraphModel.Doc modelDoc(VModel m) {
            return new GraphModel.Doc(m.text() == null ? "" : m.text(), GraphModel.encodeMetaProps(m.labels(), m.props()), m.vector(), 0);
        }

        private void writeLabels(long vid, List<String> old, List<String> neu) {
            for (BatchOp op : GraphModel.labelOps(vid, old, neu)) {
                if (op instanceof BatchOp.Delete d) write("delete", d.key(), null);
                else write("put", op.key(), ((BatchOp.Put) op).value());
            }
        }

        /**
         * 事务里分配 id 并建顶点 ⇒ vid。计数器读时登记版本（并发分配的语句在它上面串行）；越过已被占用的 id
         * （读的时候登记了「不存在」，直通写者插进来会让语句重跑）。
         */
        long allocate(GraphModel.Doc doc, List<String> lbls) {
            byte[] cur = readRaw(GraphKeys.COUNTER);
            GraphModel.Watermark nxt;
            if (cur != null) nxt = GraphModel.decodeCounter(cur);
            else {
                Long m = GraphModel.maxVid(db);
                nxt = m == null ? GraphModel.afterVid(0) : GraphModel.afterVid(m);
            }
            long vid;
            while (true) {
                if (nxt.exhausted()) throw CypherError.exec("vertex ids are exhausted");
                long v = nxt.next();
                if (readDoc(v) != null || headExists(v)) nxt = GraphModel.afterVid(v);
                else {
                    vid = v;
                    break;
                }
            }
            write("put", GraphKeys.COUNTER, GraphModel.encodeCounter(GraphModel.afterVid(vid)));
            write("put-doc", GraphKeys.nodeBytes(vid), doc);
            writeLabels(vid, List.of(), lbls);
            return vid;
        }

        /** 读改写一个顶点：f(模型) ⇒ 模型。没有 n 文档但有邻接表时从空模型开始；顶点不存在 ⇒ null。⇒ [旧 新]。 */
        VModel[] updateVertex(long vid, UnaryOperator<VModel> f) {
            GraphModel.Doc d = readDoc(vid);
            VModel old;
            if (d != null) {
                old = docModel(d);
                if (old == null) throw CypherError.exec("node ", GraphModel.vidText(vid), " has undecodable meta");
            } else if (headExists(vid)) old = VModel.EMPTY;
            else return null;
            VModel neu = f.apply(old);
            write("put-doc", GraphKeys.nodeBytes(vid), modelDoc(neu));
            writeLabels(vid, old.labels(), neu.labels());
            return new VModel[]{old, neu};
        }

        void putEdge(long s, long et, long d, long r, byte[] props) {
            sess.putEdge(s, et, d, r, props);
        }

        void delEdge(long s, long et, long d, long r) {
            sess.delEdge(s, et, d, r);
        }

        /** 删顶点：摘掉全部关联边（两端同批）、删 n 文档与它的标签索引项。 */
        void deleteVertex(long vid) {
            for (var e : sess.allEntries(vid)) {
                if (e.dir() == 0) delEdge(vid, e.etype(), e.other(), e.rank());
                else delEdge(e.other(), e.etype(), vid, e.rank());
            }
            check(vid);
            GraphModel.Doc dd = readDoc(vid);
            if (dd != null) {
                write("delete", GraphKeys.nodeBytes(vid), null);
                writeLabels(vid, GraphModel.labelsFromMeta(dd.meta()), List.of());
            }
        }

        /** 提交 ⇒ ok | conflict | noop。 */
        String commit() {
            List<BatchOp> kvOps = new ArrayList<>();
            for (KvEntry e : kv.values()) {
                if (e.write != null) {
                    switch (e.write) {
                        case "delete" -> kvOps.add(BatchOp.delete(e.key, e.cond));
                        case "put" -> kvOps.add(BatchOp.put(e.key, (byte[]) e.val, e.cond));
                        default -> {
                            GraphModel.Doc d = (GraphModel.Doc) e.val;
                            kvOps.add(BatchOp.putDoc(e.key, new Document(d.text(), null, d.vector(), d.meta(), 0), e.cond));
                        }
                    }
                } else if (e.cond != null) kvOps.add(BatchOp.check(e.key, e.cond));
            }
            // 读到一半的目录树已被别人改过（布局 2）：整条语句重跑。过期标记取算 op 之前的（同 clj：收尾里的读不计入）
            boolean stale = sess.stale();
            List<BatchOp> ops = sess.commitOps(kvOps);
            if (stale) return "conflict";
            if (ops == null || ops.stream().allMatch(o -> o instanceof BatchOp.Check)) return "noop";
            return db.tryWriteAtomic(ops).isConflict() ? "conflict" : "ok";
        }
    }
}
