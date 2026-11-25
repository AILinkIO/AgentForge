package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import io.ailink.agentforge.bitcask.ffm.Batches;
import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.Iters;
import io.ailink.agentforge.bitcask.ffm.Kv;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeHandle;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;
import io.ailink.agentforge.bitcask.ffm.Open;
import io.ailink.agentforge.bitcask.ffm.ParallelScan;
import io.ailink.agentforge.bitcask.ffm.Results;

/**
 * libbitcask 的 KV 层：打开 / 关闭、get / put / delete、批与事务（含前置条件）、游标（有序 range / keydir 扫描）、并行扫描、管理。
 * 检索见 {@link BitcaskIndex}（它也是一个 {@code Bitcask}）；meta 见 {@link Meta}。拿到陌生目录不知道怎么开 ⇒ 先 {@link #probe}
 * （不开库、不拿锁：KV 还是检索库、建库时的向量配置）。
 * <ul>
 * <li>句柄 try-with-resources 友好；同一句柄多线程读写安全；游标一个线程一个。忘了关由 Cleaner 兜底。关后再用 ⇒ {@code bitcask/disposed}。</li>
 * <li>key / value：byte[]，或字符串（按 UTF-8）。</li>
 * <li>错误一律 {@link BitcaskException}（按 {@link BitcaskException#code()} 分支）。</li>
 * <li>批是数据：{@code List<BatchOp>}。</li>
 * <li>集合式遍历（{@link #forEachRange} / {@link #rangeEntries} / {@link #rangeStream} 等）每次开一个游标、按批取、
 *     结束（含提前停 / 抛异常）即释放；{@code xxxStream} 要关（try-with-resources）。</li>
 * </ul>
 * 盘上格式与 keelson clj-bitcask 相同（同一份 libbitcask），两边可以互开同一个目录。
 */
public class Bitcask implements AutoCloseable {
    final NativeHandle.CaskHandle handle;
    private final Path directory;
    private final int vectorDimension;
    private final boolean index;

    Bitcask(NativeHandle.CaskHandle handle, Path directory, int vectorDimension, boolean index) {
        this.handle = handle;
        this.directory = directory;
        this.vectorDimension = vectorDimension;
        this.index = index;
    }

    // ═══ 打开 / 关闭 ═══

    static Path checkDirectory(Path dir) {
        if (dir == null || dir.toString().isEmpty()) throw new IllegalArgumentException("directory");
        return dir;
    }

    static NativeHandle.CaskHandle openHandle(Path dir, Options opts, MemorySegment o, NativeArena a, String typeName) {
        MergePolicy policy = opts.mergePolicy();
        if (policy != null) policy.validate();
        BiConsumer<Options.LogLevel, String> log = opts.log();
        Open.LogSink sink = log == null ? null : (level, msg) -> log.accept(level == 0 ? Options.LogLevel.WARNING : Options.LogLevel.ERROR, msg);
        return Open.open(dir.toString(), o, policy == null ? MemorySegment.NULL : policy.toNative(a), opts.tuningToNative(a), sink, typeName);
    }

    /** 打开（不存在则创建）一个纯 KV 库，缺省选项。 */
    public static Bitcask open(Path dir) {
        return open(dir, null);
    }

    /** 打开（不存在则创建）一个纯 KV 库（选项里的检索段忽略）。 */
    public static Bitcask open(Path dir, Options opts) {
        checkDirectory(dir);
        Options o = opts == null ? Options.defaults() : opts;
        try (var a = new NativeArena()) {
            MemorySegment no = a.alloc(NativeTypes.OPTIONS);
            BitcaskNative.optionsInit(no);
            o.kvToNative(no);
            return new Bitcask(openHandle(dir, o, no, a, "BitcaskKv"), dir, 0, false);
        }
    }

    public static Bitcask open(String dir) {
        return open(Path.of(dir), null);
    }

    public static Bitcask open(String dir, Options opts) {
        return open(Path.of(dir), opts);
    }

    /** 打开检索库（同 {@link BitcaskIndex#open(Path, Options)}）。 */
    public static BitcaskIndex openIndex(Path dir, Options opts) {
        return BitcaskIndex.open(dir, opts);
    }

    /**
     * 不开库、不拿锁地看一眼 {@code dir} 是什么库（⛔ 不创建任何东西）。错误：{@code bitcask/probe-not-directory}（不存在 / 不是目录）、
     * {@code bitcask/probe-not-bitcask}（没有 bitcask.meta）、{@code bitcask/probe-corrupt}（截断 / magic / CRC / 自相矛盾）、
     * {@code bitcask/probe-unsupported}（旧格式要迁移 / 重建，或本版认不得的新值）。
     */
    public static ProbeResult probe(Path dir) {
        return Probe.probe(dir);
    }

    /** 关闭（幂等）。还开着的游标会把真正的关库推迟到它们释放之后。 */
    @Override
    public void close() {
        handle.close();
    }

    public Path directory() {
        return directory;
    }

    public boolean isIndex() {
        return index;
    }

    /** 检索库建库时的向量维度（KV 库 / 无向量 ⇒ 0）。 */
    public int vectorDimension() {
        return vectorDimension;
    }

    public boolean isClosed() {
        return handle.isClosed();
    }

    @Override
    public String toString() {
        return (index ? "#bitcask/index " : "#bitcask/kv ") + '"' + directory + '"';
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String str(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    // ═══ 写 ═══

    /** 写入（覆盖）。 */
    public void put(byte[] key, byte[] value) {
        Kv.put(handle, key, value);
    }

    public void put(String key, String value) {
        Kv.put(handle, utf8(key), utf8(value));
    }

    public void put(String key, byte[] value) {
        Kv.put(handle, utf8(key), value);
    }

    /** 写入并设绝对过期（到期后 get / 迭代视作不存在；null = 永不；早于纪元 ⇒ 立即过期）。 */
    public void put(byte[] key, byte[] value, Instant expiresAt) {
        if (expiresAt == null) put(key, value);
        else Kv.putEx(handle, key, value, Document.expirySeconds(expiresAt));
    }

    public void put(String key, String value, Instant expiresAt) {
        put(utf8(key), utf8(value), expiresAt);
    }

    /** 写入数组区间（免得调用方先切数组）。 */
    public void putRange(byte[] key, int keyOffset, int keyLength, byte[] value, int valueOffset, int valueLength) {
        Kv.put(handle, key, keyOffset, keyLength, value, valueOffset, valueLength);
    }

    /** 删除（写墓碑；key 不存在不报错）。 */
    public void delete(byte[] key) {
        Kv.delete(handle, key);
    }

    public void delete(String key) {
        Kv.delete(handle, utf8(key));
    }

    // ═══ 读 ═══

    /** 读值（拷贝）；不存在 ⇒ null。 */
    public byte[] get(byte[] key) {
        return Kv.get(handle, key);
    }

    public byte[] get(String key) {
        return Kv.get(handle, utf8(key));
    }

    /** 读值并按 UTF-8 解码；不存在 ⇒ null。 */
    public String getString(byte[] key) {
        return str(get(key));
    }

    public String getString(String key) {
        return str(get(key));
    }

    /** 判存在（一次 C 调用，命中即释放结果：不拷值）。空值也算存在；过期 ⇒ 不存在。 */
    public boolean containsKey(byte[] key) {
        return Kv.contains(handle, key);
    }

    public boolean containsKey(String key) {
        return Kv.contains(handle, utf8(key));
    }

    /**
     * 零拷贝读：借 C 侧缓冲的记录（用完 close）；不存在 ⇒ null。{@code value()} 等拷出，{@code valueSegment()} 等是只读视图
     * （记录关后再碰 ⇒ IllegalStateException）。
     */
    public Record readRecord(byte[] key) {
        var r = Kv.read(handle, key);
        return r == null ? null : new Record(r);
    }

    public Record readRecord(byte[] key, int offset, int length) {
        var r = Kv.read(handle, key, offset, length);
        return r == null ? null : new Record(r);
    }

    public Record readRecord(String key) {
        return readRecord(utf8(key));
    }

    /**
     * 读值并带回 key 当前记录的 LSN（一次 C 调用）；不存在 ⇒ null（libbitcask 8.0.0）。ord 只在 key 被写入时改变
     * （merge 搬迁、重开、索引重建都原样保留），可直接当版本号：批 op 带 {@link Condition#ord}，提交时有人改过这个 key 就整批不写。
     */
    public VersionedValue getWithOrd(byte[] key) {
        long[] o = new long[1];
        byte[] v = Kv.getWithOrd(handle, key, o);
        return v == null ? null : new VersionedValue(v, o[0]);
    }

    public VersionedValue getWithOrd(String key) {
        return getWithOrd(utf8(key));
    }

    /**
     * 读回整篇文档（{@code bitcask_get_doc}，KV 库与检索库都能读；纯 KV 写入的值按正文读回）。meta / vector 没有 ⇒ null；
     * fields 保持写入序。不存在 ⇒ null。
     */
    public StoredDocument getDocument(byte[] key) {
        Kv.DocView d = Kv.getDoc(handle, key);
        if (d == null) return null;
        return new StoredDocument(d.text(), d.meta().length > 0 ? d.meta() : null, d.vector().length > 0 ? d.vector() : null,
                Collections.unmodifiableMap(new LinkedHashMap<>(d.fields())), d.tstamp(), d.ord());
    }

    public StoredDocument getDocument(String key) {
        return getDocument(utf8(key));
    }

    // ═══ 批 ═══

    /** 编译好的批（平行数组，交给 {@link Batches}）。 */
    private record Compiled(byte[] kinds, byte[][] keys, Object[] values, byte[] expects, long[] ords, int n, boolean cond) {
    }

    private static Compiled compile(List<BatchOp> ops) {
        int n = ops.size();
        byte[] kinds = new byte[n];
        byte[][] keys = new byte[n][];
        Object[] values = new Object[n];
        byte[] expects = new byte[n];
        long[] ords = new long[n];
        boolean cond = false;
        for (int i = 0; i < n; i++) {
            BatchOp op = Objects.requireNonNull(ops.get(i), "op");
            keys[i] = op.key();
            switch (op) {
                case BatchOp.Put p -> {
                    kinds[i] = Batches.PUT;
                    values[i] = p.value();
                }
                case BatchOp.Delete d -> kinds[i] = Batches.REMOVE;
                case BatchOp.PutDoc d -> kinds[i] = Batches.PUT_DOC;
                case BatchOp.Check c -> kinds[i] = Batches.CHECK;
            }
            if (op instanceof BatchOp.PutDoc d) values[i] = d.doc();
            switch (op.condition()) {
                case null -> expects[i] = Batches.EXPECT_NONE;
                case Condition.Absent a -> {
                    expects[i] = Batches.EXPECT_ABSENT;
                    cond = true;
                }
                case Condition.Ord o -> {
                    expects[i] = Batches.EXPECT_ORD;
                    ords[i] = o.ord();
                    cond = true;
                }
            }
        }
        return new Compiled(kinds, keys, values, expects, ords, n, cond);
    }

    private BatchResult submitCond(int mode, Compiled c) {
        long[] out = new long[c.n];
        long failed = Batches.submitCond(handle, mode, c.kinds, c.keys, c.values, c.expects, c.ords, c.n, out);
        return failed < 0 ? BatchResult.success(out) : BatchResult.conflictAt(failed);
    }

    /**
     * {@code bitcask_put_batch}：整批一次提交，本进程内 all-or-nothing 可见；崩溃后磁盘可能留前缀。只收无条件的 put
     * （有 delete / putDoc / 条件 ⇒ {@code bitcask/put-batch-put-only}；要混写用 {@link #writeAtomic} / {@link #commit}）。空批什么都不做。
     */
    public void writeBatch(List<BatchOp> ops) {
        Compiled c = compile(ops);
        for (byte k : c.kinds) if (k != Batches.PUT) throw BitcaskException.of("bitcask/put-batch-put-only");
        if (c.cond) throw BitcaskException.of("bitcask/put-batch-put-only");
        if (c.n == 0) return;
        byte[][] vs = new byte[c.n][];
        for (int i = 0; i < c.n; i++) vs[i] = (byte[]) c.values[i];
        Batches.putBatch(handle, c.keys, vs, c.n);
    }

    /**
     * {@code bitcask_put_batch_atomic}：引擎原子批，崩溃 / 掉电后全有或全无；批内按序 apply（同 key 后写赢）。
     * 有文档 ⇒ {@code *_ex}（文档与它的二级索引项同批落盘）。空批什么都不做。
     * op 可带前置条件（见 {@link Condition}）：任一不成立 ⇒ 抛 {@code bitcask/condition-failed}
     * （{@link BitcaskException#failedOp()} = 下标），整批零副作用。不想用异常分流冲突就用 {@link #tryWriteAtomic}。
     */
    public void writeAtomic(List<BatchOp> ops) {
        Compiled c = compile(ops);
        if (c.n == 0) return;
        if (c.cond) {
            BatchResult r = submitCond(Batches.ATOMIC, c);
            if (r.isConflict()) throw BitcaskException.conditionFailed(r.conflict());
        } else {
            Batches.submit(handle, Batches.ATOMIC, c.kinds, c.keys, c.values, c.n);
        }
    }

    /**
     * 同 {@link #writeAtomic}，但冲突不抛：成功 ⇒ 各 op 落盘记录的 LSN（check 为 0）；条件不成立 ⇒ 冲突下标，整批零副作用。
     * 其它错误照常抛。空批 ⇒ 成功、ords 为空。
     */
    public BatchResult tryWriteAtomic(List<BatchOp> ops) {
        Compiled c = compile(ops);
        if (c.n == 0) return BatchResult.success(new long[0]);
        return submitCond(Batches.ATOMIC, c);
    }

    /** {@link #commit(List, boolean)}，提交后 fsync。 */
    public void commit(List<BatchOp> ops) {
        commit(ops, true);
    }

    /**
     * {@code bitcask_txn_commit}：原子批 + 事务级校验（空批、空 key、重复 key、{@code _txn:} 前缀 ⇒ {@code bitcask/invalid-option}，
     * 零副作用）。{@code sync} = 提交后 fsync。op 可带前置条件，语义同 {@link #writeAtomic}
     * （check 的 key 不能再出现在别的 op 里——要校验的写把条件挂在那条 op 上）。
     */
    public void commit(List<BatchOp> ops, boolean sync) {
        Compiled c = compile(ops);
        int mode = sync ? Batches.COMMIT_SYNC : Batches.COMMIT;
        if (c.cond && c.n > 0) {
            BatchResult r = submitCond(mode, c);
            if (r.isConflict()) throw BitcaskException.conditionFailed(r.conflict());
        } else {
            Batches.submit(handle, mode, c.kinds, c.keys, c.values, c.n);
        }
    }

    // ═══ 游标 ═══

    private static final long RO_LO = NativeTypes.RO_LO, RO_HI = NativeTypes.RO_HI, RO_PREFETCH = NativeTypes.RO_PREFETCH;

    /** 全库有序游标。 */
    public Cursor rangeCursor() {
        return rangeCursor(RangeOptions.all());
    }

    /**
     * 有序 range 游标：按 key 字典序遍历 [lo, hi)，只产出活 key；key 集合与值固定在开游标那一刻（libbitcask 13.1.0）。
     * 只读打开而目录没有有序索引（OKI）⇒ libbitcask 从数据建一份<b>内存</b>读视图（不写盘），照常遍历；
     * 读写打开时 OKI 重建失败 ⇒ {@code bitcask/index-rebuild-failed}。
     */
    public Cursor rangeCursor(RangeOptions opts) {
        RangeOptions o = opts == null ? RangeOptions.all() : opts;
        try (var a = new NativeArena()) {
            MemorySegment ro = a.alloc(NativeTypes.RANGE_OPTIONS);
            BitcaskNative.rangeOptionsInit(ro);
            ro.set(ValueLayout.JAVA_LONG, RO_PREFETCH, Math.max(0, o.prefetch));
            if (o.lo != null) a.setBytesSlice(ro, RO_LO, o.lo, 0, o.lo.length);
            else NativeTypes.setSlice(ro, RO_LO, null, 0);
            if (o.hi != null) a.setBytesSlice(ro, RO_HI, o.hi, 0, o.hi.length);
            else NativeTypes.setSlice(ro, RO_HI, null, 0);
            MemorySegment f = FilterEncoder.toNative(o.filter, a);
            return Cursor.of(Iters.openRange(handle, ro, o.meta || o.filter != null, f), true);
        }
    }

    /** 全库 keydir 快照游标（无序）。 */
    public Cursor scanCursor() {
        return scanCursor(ScanOptions.all());
    }

    /** keydir 快照游标（无序）。 */
    public Cursor scanCursor(ScanOptions opts) {
        ScanOptions o = opts == null ? ScanOptions.all() : opts;
        try (var a = new NativeArena()) {
            MemorySegment p = o.prefix != null ? a.slice(o.prefix) : a.emptySlice();
            MemorySegment f = FilterEncoder.toNative(o.filter, a);
            return Cursor.of(Iters.openScan(handle, p, o.meta || o.filter != null, f), false);
        }
    }

    private static long drain(Cursor c, int batch, Predicate<Entry> visitor) {
        long n = 0;
        while (true) {
            Entry[] es = c.nextBatchArray(batch);
            if (es.length == 0) return n;
            for (Entry e : es) {
                n++;
                if (!visitor.test(e)) return n;
            }
        }
    }

    private static long drainCursor(Cursor c, Predicate<Cursor> visitor) {
        long n = 0;
        while (c.moveNext()) {
            n++;
            if (!visitor.test(c)) return n;
        }
        return n;
    }

    /** 有序遍历：每条拷出后交给 {@code visitor}，它回 false 即停。回交出去的条数。游标在返回前释放。 */
    public long forEachRange(RangeOptions opts, Predicate<Entry> visitor) {
        RangeOptions o = opts == null ? RangeOptions.all() : opts;
        try (Cursor c = rangeCursor(o)) {
            return drain(c, o.batch, visitor);
        }
    }

    /** 零拷贝有序遍历：{@code visitor} 拿定位在当前条目上的游标（视图只在那一步内有效），回 false 即停。 */
    public long forEachRangeCursor(RangeOptions opts, Predicate<Cursor> visitor) {
        try (Cursor c = rangeCursor(opts)) {
            return drainCursor(c, visitor);
        }
    }

    /** 有序 range 收齐（确定性释放，不是惰性的）。 */
    public List<Entry> rangeEntries(RangeOptions opts) {
        List<Entry> out = new ArrayList<>();
        forEachRange(opts, out::add);
        return out;
    }

    public List<Entry> rangeEntries() {
        return rangeEntries(RangeOptions.all());
    }

    /** 惰性有序流（按批取）；⚠️ 要关（try-with-resources），关流即释放游标。 */
    public Stream<Entry> rangeStream(RangeOptions opts) {
        RangeOptions o = opts == null ? RangeOptions.all() : opts;
        Cursor c = rangeCursor(o);
        return stream(c, o.batch);
    }

    /** keydir 快照遍历（同 {@link #forEachRange}）。 */
    public long forEachScan(ScanOptions opts, Predicate<Entry> visitor) {
        ScanOptions o = opts == null ? ScanOptions.all() : opts;
        try (Cursor c = scanCursor(o)) {
            return drain(c, o.batch, visitor);
        }
    }

    public long forEachScanCursor(ScanOptions opts, Predicate<Cursor> visitor) {
        try (Cursor c = scanCursor(opts)) {
            return drainCursor(c, visitor);
        }
    }

    public List<Entry> scanEntries(ScanOptions opts) {
        List<Entry> out = new ArrayList<>();
        forEachScan(opts, out::add);
        return out;
    }

    public List<Entry> scanEntries() {
        return scanEntries(ScanOptions.all());
    }

    /** 惰性快照流；⚠️ 要关。 */
    public Stream<Entry> scanStream(ScanOptions opts) {
        ScanOptions o = opts == null ? ScanOptions.all() : opts;
        Cursor c = scanCursor(o);
        return stream(c, o.batch);
    }

    private static Stream<Entry> stream(Cursor c, int batch) {
        var it = new java.util.Iterator<Entry>() {
            Entry[] buf = new Entry[0];
            int i;
            boolean done;

            @Override
            public boolean hasNext() {
                if (i < buf.length) return true;
                if (done) return false;
                buf = c.nextBatchArray(batch);
                i = 0;
                if (buf.length == 0) {
                    done = true;
                    c.close();
                }
                return buf.length > 0;
            }

            @Override
            public Entry next() {
                if (!hasNext()) throw new NoSuchElementException();
                return buf[i++];
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(c::close);
    }

    // ═══ 并行扫描 ═══

    /** 全表并行扫描（拷贝），硬件线程数个线程。见 {@link #parallelScan(byte[], int, BiConsumer)}。 */
    public long parallelScan(BiConsumer<byte[], byte[]> f) {
        return parallelScan(null, 0, f);
    }

    /**
     * 并行扫描（{@code bitcask_parallel_scan_prefix}）：调用线程快照活 key（可按前缀），再分 {@code threads} 段并发读值、
     * 逐条 {@code f(key, value)}（拷贝）。⚠️ f 从多条原生线程并发调用，须线程安全。f 抛的第一个异常在扫描结束后原样重抛
     * （之后不再回调）。{@code threads} = 0 ⇒ 硬件线程数；{@code prefix} null ⇒ 全表。返回遍历到的 key 数。
     */
    public long parallelScan(byte[] prefix, int threads, BiConsumer<byte[], byte[]> f) {
        return ParallelScan.run(handle, prefix, threads, true, (k, v) -> f.accept((byte[]) k, (byte[]) v));
    }

    /** 同 {@link #parallelScan(byte[], int, BiConsumer)}，但回调拿只读视图（只在回调期间有效；零拷贝）。 */
    public long parallelScanViews(byte[] prefix, int threads, BiConsumer<MemorySegment, MemorySegment> f) {
        return ParallelScan.run(handle, prefix, threads, false, (k, v) -> f.accept((MemorySegment) k, (MemorySegment) v));
    }

    // ═══ 管理 ═══

    @FunctionalInterface
    private interface FaultCall {
        int call(MemorySegment cask, MemorySegment fault);
    }

    private void faultCall(String op, FaultCall call) {
        MemorySegment cask = handle.acquire();
        try (var a = new NativeArena()) {
            MemorySegment f = a.fault();
            Faults.check(call.call(cask, f), f, op);
        } finally {
            handle.release();
        }
    }

    /** fsync 当前活动 data 文件。 */
    public void sync() {
        faultCall("bitcask_sync", BitcaskNative::sync);
    }

    /** 手动 checkpoint：keydir 快照 + 检索 ckpt 落盘，顺带回收上轮 merge 退休的文件。 */
    public void checkpoint() {
        faultCall("bitcask_checkpoint", BitcaskNative::checkpoint);
    }

    /** 关闭 active 写文件、释放写锁；下次写自动开新文件。 */
    public void closeWriteFile() {
        faultCall("bitcask_close_write_file", BitcaskNative::closeWriteFile);
    }

    /** 按策略 merge（不需要就什么都不做）。 */
    public void merge() {
        faultCall("bitcask_merge", BitcaskNative::merge);
    }

    /**
     * 显式 merge 这些文件（{@link #needsMerge} 给的那种路径；{@code bitcask_merge_files}）：空表 ⇒ {@code bitcask/merge-files-empty}；
     * 不是 data 文件 / 是 active 写文件 ⇒ {@code bitcask/invalid-option}，不碰盘。
     * ⚠️ 并掉的输入文件只是退休：到 checkpoint / 下次 merge / 关库才删。
     */
    public void merge(List<String> files) {
        if (files.isEmpty()) throw BitcaskException.of("bitcask/merge-files-empty");
        for (String f : files) if (f == null || f.isEmpty()) throw BitcaskException.of("bitcask/merge-files-blank");
        MemorySegment cask = handle.acquire();
        try (var a = new NativeArena()) {
            MemorySegment ptrs = a.alloc(ValueLayout.ADDRESS, files.size());
            for (int i = 0; i < files.size(); i++) ptrs.setAtIndex(ValueLayout.ADDRESS, i, a.utf8z(files.get(i)));
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.mergeFiles(cask, ptrs, files.size(), f), f, "bitcask_merge_files");
        } finally {
            handle.release();
        }
    }

    /** 问 merge 策略要不要 merge（候选可直接交给 {@link #merge(List)}）。 */
    public MergeAdvice needsMerge() {
        MemorySegment cask = handle.acquire();
        try (var a = new NativeArena()) {
            MemorySegment nm = a.alloc(NativeTypes.NEEDS_MERGE);
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.needsMerge(cask, nm, f), f, "bitcask_needs_merge");
            try {
                return new MergeAdvice(nm.get(ValueLayout.JAVA_INT, 0) != 0, List.of(Results.needsMergeFiles(nm)));
            } finally {
                BitcaskNative.needsMergeFree(nm);
            }
        } finally {
            handle.release();
        }
    }

    /** 兼容入口。libbitcask 6.9.0 起 OKI 视图自带不可变来源，不再有 pending freeze；恒为 false。 */
    public boolean isFrozen() {
        MemorySegment cask = handle.acquire();
        try {
            return BitcaskNative.isFrozen(cask) != 0;
        } finally {
            handle.release();
        }
    }

    /** 是否从没写过 key（写过再删光仍为 false）。 */
    public boolean isEmpty() {
        MemorySegment cask = handle.acquire();
        try {
            return BitcaskNative.isEmpty(cask) != 0;
        } finally {
            handle.release();
        }
    }

    /** 状态（{@code bitcask_status_ex}）。 */
    public Status status() {
        MemorySegment cask = handle.acquire();
        try (var a = new NativeArena()) {
            MemorySegment s = a.alloc(NativeTypes.STATUS_EX);
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.statusEx(cask, s, f), f, "bitcask_status_ex");
            long[] v = new long[7];
            for (int i = 0; i < 7; i++) v[i] = s.get(ValueLayout.JAVA_LONG, 8L * i);
            return new Status(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
        } finally {
            handle.release();
        }
    }
}
