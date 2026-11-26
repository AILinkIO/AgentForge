package io.ailink.agentforge.agent.store;

import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.bitcask.BatchOp;
import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskIndex;
import io.ailink.agentforge.bitcask.Document;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.Meta;
import io.ailink.agentforge.bitcask.Options;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.SearchOptions;
import io.ailink.agentforge.bitcask.StoredDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * bitcask 上的 {@link SessionStore}（coxswain {@code store.ss} 的 SQLite + FTS5 换成 KV + 检索库）。
 *
 * <h2>两个库：KV 是真相，检索库是投影</h2>
 * 实测 libbitcask 的检索库连**普通 put 的值也进索引**，所以消息 JSON（签名 base64、整份工具结果）不能和全文索引放一个库 ——
 * 否则就是 {@link SessionStore} 那条"能搜到的 ≠ 存下来的"被悄悄打破。于是：
 * <ul>
 *   <li>{@code <dir>/kv}（{@link Bitcask}）：
 *     <ul>
 *       <li>{@code meta/schema} → 版本号；{@code meta/next-conv} → 下一个对话 id（单调，id 永不复用）；</li>
 *       <li>{@code conv/<20 位 id>} → {@code {id,title,created,updated,parent,sid,messages}}；</li>
 *       <li>{@code msg/<20 位 conv>/<10 位 seq>} → {@link MessageCodec} 的 JSON（seq 从 0 连续，零填充 ⇒ 字节序即顺序）；</li>
 *       <li>{@code cmp/<20 位 conv>/<10 位 at>} → {@code {at,tail}}（按 at 排，最后一条算数）；</li>
 *       <li>（旧版的 {@code perm/<root>} 审批规则已废弃：执行规则改存 {@code .rules} 文件，库里残留的键不再读。）</li>
 *     </ul></li>
 *   <li>{@code <dir>/fts}（{@link BitcaskIndex}，trigram）：{@code fts/<conv>/<seq>} → 正文 = {@link Reminder#shownText}（空的不写），
 *       meta {@code {role}}。</li>
 * </ul>
 *
 * <h2>两库之间不能原子，靠写序兜住</h2>
 * 追加：先写索引、再一批原子写 KV（消息 + 对话记录的条数）；删除：先一批原子删 KV、再删索引。于是崩在中间只会留下
 * **多出来的**索引项，检索时按"对话还在、seq &lt; 条数"过滤掉；以后同一个 seq 再写会把它覆盖（对话 id 不复用，不会撞别人）。
 *
 * <h2>检索 = 索引取候选 + 逐条核子串</h2>
 * 契约是"子串、大小写不敏感"。实测 ngram 分词器对**汉字**切 n-gram（子串必然共享 n-gram，BM25 能取全），对**拉丁字母按整词**切
 * （{@code ello} 搜不到 {@code Hello}）。所以：候选一律再按子串核一遍（BM25 的 OR 语义会多给）；查询含 ASCII 字母数字、或候选
 * 撑满上限时，退回扫一遍投影 —— 宁可慢一点，也不能让"没搜到"被读成"没说过"。
 */
public final class BitcaskSessionStore implements SessionStore {

    static final String SCHEMA = "1";
    private static final String K_SCHEMA = "meta/schema", K_NEXT = "meta/next-conv";
    private static final String CONV = "conv/", MSG = "msg/", CMP = "cmp/", FTS = "fts/";
    /** 一次向索引要多少候选；撑满就退回扫描（候选被截断时"新的在前"不再可靠）。 */
    static final int CANDIDATES = 500;

    private final Bitcask kv;
    private final BitcaskIndex fts;
    private long nextId;
    private long lastNow;

    private BitcaskSessionStore(Bitcask kv, BitcaskIndex fts) {
        this.kv = kv;
        this.fts = fts;
        String schema = kv.getString(K_SCHEMA);
        if (schema == null) kv.put(K_SCHEMA, SCHEMA);
        else if (!schema.equals(SCHEMA)) {
            // 新库配老程序：停下，不将就着读（将就的损坏是静默的）；但话要说全 —— 数据一条没少
            throw new IllegalStateException("会话库版本 " + schema + " 比这个程序认得的（" + SCHEMA + "）新：库里的对话一条没少，换回新版本就读得到");
        }
        String n = kv.getString(K_NEXT);
        long next = n == null ? 1 : Long.parseLong(n.strip());
        for (ObjectNode c : allConvs()) {
            next = Math.max(next, c.path("id").asLong() + 1);
            lastNow = Math.max(lastNow, c.path("updated").asLong());
        }
        this.nextId = next;
    }

    /** 打开（不存在则建）{@code dir} 下的会话库。libbitcask 装不上 / 库被别的进程占着会抛 —— 由调用方决定退回内存库。 */
    public static BitcaskSessionStore open(Path dir) {
        try {
            Files.createDirectories(dir.resolve("kv"));
            Files.createDirectories(dir.resolve("fts"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Bitcask kv = Bitcask.open(dir.resolve("kv"));
        try {
            BitcaskIndex ix = BitcaskIndex.open(dir.resolve("fts"),
                    Options.defaults().analyzer(Options.Analyzer.NGRAM).ngramMin(3).ngramMax(3));
            try {
                return new BitcaskSessionStore(kv, ix);
            } catch (RuntimeException e) {
                ix.close();
                throw e;
            }
        } catch (RuntimeException e) {
            kv.close();
            throw e;
        }
    }

    // ── 键 ───────────────────────────────────────────────────────────────

    static String convKey(long id) { return CONV + String.format("%020d", id); }

    static String convPrefix(String ns, long conv) { return ns + String.format("%020d", conv) + "/"; }

    static String seqKey(String ns, long conv, int seq) { return convPrefix(ns, conv) + String.format("%010d", seq); }

    /** {@code fts/<conv>/<seq>} → {conv, seq}；认不出 null。 */
    static long[] parseSeqKey(String key) {
        int a = key.indexOf('/'), b = key.lastIndexOf('/');
        if (a < 0 || b <= a) return null;
        try {
            return new long[] {Long.parseLong(key.substring(a + 1, b)), Long.parseLong(key.substring(b + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 对话记录 ─────────────────────────────────────────────────────────

    private long now() {
        long t = System.currentTimeMillis();
        lastNow = Math.max(t, lastNow + 1);
        return lastNow;
    }

    private ObjectNode readConv(long id) {
        String s = kv.getString(convKey(id));
        return s == null ? null : (ObjectNode) AgentTool.JSON.readTree(s);
    }

    private ObjectNode need(long conv) {
        ObjectNode c = readConv(conv);
        if (c == null) throw new IllegalArgumentException("没有这段对话：" + conv);
        return c;
    }

    private List<ObjectNode> allConvs() {
        List<ObjectNode> out = new ArrayList<>();
        for (Entry e : kv.rangeEntries(RangeOptions.prefix(CONV))) out.add((ObjectNode) AgentTool.JSON.readTree(e.valueString()));
        return out;
    }

    private static Long parentOf(JsonNode c) {
        JsonNode p = c.get("parent");
        return p == null || p.isNull() ? null : p.asLong();
    }

    private static Conversation view(JsonNode c) {
        JsonNode sid = c.get("sid");
        return new Conversation(c.path("id").asLong(), c.path("title").asString(""), c.path("created").asLong(),
                c.path("updated").asLong(), parentOf(c), sid == null || sid.isNull() ? null : sid.asString(), c.path("messages").asInt(),
                textOrNull(c, "project"), textOrNull(c, "root"));
    }

    private static String textOrNull(JsonNode c, String k) {
        JsonNode v = c.get(k);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static final Comparator<ObjectNode> RECENT_FIRST =
            Comparator.comparingLong((ObjectNode c) -> c.path("updated").asLong()).reversed()
                    .thenComparing(Comparator.comparingLong((ObjectNode c) -> c.path("id").asLong()).reversed());

    @Override
    public synchronized long newConversation(String title, Long parent, String sid) {
        long id = nextId;
        long t = now();
        ObjectNode c = AgentTool.JSON.createObjectNode();
        c.put("id", id).put("title", title == null ? "" : title).put("created", t).put("updated", t);
        if (parent == null) c.putNull("parent"); else c.put("parent", parent);
        if (sid == null) c.putNull("sid"); else c.put("sid", sid);
        c.put("messages", 0);
        kv.writeAtomic(List.of(BatchOp.put(convKey(id), c.toString()), BatchOp.put(K_NEXT, Long.toString(id + 1))));
        nextId = id + 1;
        return id;
    }

    @Override
    public synchronized void setTitle(long conv, String title) {
        ObjectNode c = need(conv);
        c.put("title", title == null ? "" : title);
        kv.put(convKey(conv), c.toString());
    }

    @Override
    public synchronized void setPlacement(long conv, String project, String root) {
        ObjectNode c = need(conv);
        if (project == null) c.putNull("project"); else c.put("project", project);
        if (root == null) c.putNull("root"); else c.put("root", root);
        kv.put(convKey(conv), c.toString());
    }

    @Override
    public synchronized void deleteConversation(long conv) {
        for (Conversation ch : children(conv)) deleteConversation(ch.id());
        List<BatchOp> ops = new ArrayList<>();
        ops.add(BatchOp.delete(convKey(conv)));
        for (String ns : List.of(MSG, CMP)) {
            for (Entry e : kv.rangeEntries(RangeOptions.prefix(convPrefix(ns, conv)))) ops.add(BatchOp.delete(e.key()));
        }
        kv.writeAtomic(ops);                       // ⚠️ 先删真相：崩在这之后只剩孤儿索引项（检索时滤掉）
        List<BatchOp> ix = new ArrayList<>();
        for (Entry e : fts.rangeEntries(RangeOptions.prefix(convPrefix(FTS, conv)))) ix.add(BatchOp.delete(e.key()));
        if (!ix.isEmpty()) fts.writeAtomic(ix);
    }

    @Override
    public synchronized List<Conversation> conversations(int limit) {
        List<Conversation> out = allConvs().stream().filter(c -> parentOf(c) == null).sorted(RECENT_FIRST)
                .map(BitcaskSessionStore::view).toList();
        return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
    }

    /** 按建的顺序（id 升序 = 派活的先后）。 */
    @Override
    public synchronized List<Conversation> children(long conv) {
        return allConvs().stream().filter(c -> { Long p = parentOf(c); return p != null && p == conv; })
                .map(BitcaskSessionStore::view).toList();
    }

    @Override
    public synchronized Optional<Conversation> conversation(long conv) {
        ObjectNode c = readConv(conv);
        return c == null ? Optional.empty() : Optional.of(view(c));
    }

    // ── 消息 ─────────────────────────────────────────────────────────────

    @Override
    public synchronized void append(long conv, List<Message> msgs) {
        ObjectNode c = need(conv);
        int have = c.path("messages").asInt();
        List<BatchOp> ix = new ArrayList<>();
        List<BatchOp> ops = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            int seq = have + i;
            ops.add(BatchOp.put(seqKey(MSG, conv, seq), MessageCodec.toJson(m)));
            // 空文本不进索引：工具结果消息的可见文本本来就是空的，"不索引工具结果"自然发生
            String text = Reminder.shownText(m);
            if (!text.isEmpty()) {
                ix.add(BatchOp.putDoc(seqKey(FTS, conv, seq),
                        Document.builder().text(text).meta(Map.of("role", m.role().wire())).build()));
            }
        }
        c.put("messages", have + msgs.size());
        c.put("updated", now());
        ops.add(BatchOp.put(convKey(conv), c.toString()));
        if (!ix.isEmpty()) fts.writeAtomic(ix);    // ⚠️ 先写投影：崩在中间只会多出索引项，不会少
        kv.writeAtomic(ops);                       // 半批消息落盘会让历史不配平：消息与条数同一批
    }

    @Override
    public synchronized int sync(long conv, List<Message> history) {
        int have = need(conv).path("messages").asInt();
        if (history.size() < have) {
            throw new IllegalStateException("对话 " + conv + " 在库里有 " + have + " 条，传进来的只有 " + history.size()
                    + " 条：要么传错了对话，要么历史被截断过 —— 不悄悄接着写");
        }
        if (history.size() == have) return 0;
        append(conv, history.subList(have, history.size()));
        return history.size() - have;
    }

    @Override
    public synchronized List<Message> messages(long conv) {
        need(conv);
        List<Message> out = new ArrayList<>();
        for (Entry e : kv.rangeEntries(RangeOptions.prefix(convPrefix(MSG, conv)))) out.add(MessageCodec.fromJson(e.valueString()));
        return out;
    }

    @Override
    public synchronized List<Compaction.Pointer> compactions(long conv) {
        need(conv);
        List<Compaction.Pointer> out = new ArrayList<>();
        for (Entry e : kv.rangeEntries(RangeOptions.prefix(convPrefix(CMP, conv)))) {
            JsonNode n = AgentTool.JSON.readTree(e.valueString());
            out.add(new Compaction.Pointer(n.path("at").asInt(), n.path("tail").asInt()));
        }
        return out;
    }

    @Override
    public synchronized void addCompaction(long conv, Compaction.Pointer p) {
        need(conv);
        kv.put(seqKey(CMP, conv, p.at()), AgentTool.JSON.createObjectNode().put("at", p.at()).put("tail", p.tail()).toString());
    }

    @Override
    public synchronized int truncate(int keep) {
        List<Conversation> all = conversations(0);
        int n = 0;
        for (int i = Math.max(0, keep); i < all.size(); i++) { deleteConversation(all.get(i).id()); n++; }
        return n;
    }

    // ── 检索 ─────────────────────────────────────────────────────────────

    private record Found(long conv, int seq, String role, String snippet) { }

    @Override
    public synchronized List<SearchHit> search(String query, int limit) {
        String q = SessionStore.checkQuery(query).toLowerCase(Locale.ROOT);
        Map<Long, ObjectNode> convs = new HashMap<>();
        for (ObjectNode c : allConvs()) convs.put(c.path("id").asLong(), c);
        Map<String, Found> found = new LinkedHashMap<>();
        fts.flush();                                 // 索引是 near-real-time 的：刚写的也要搜得到
        List<io.ailink.agentforge.bitcask.SearchHit> cands = fts.searchText(q, SearchOptions.k(CANDIDATES));
        for (io.ailink.agentforge.bitcask.SearchHit h : cands) {
            if (found.containsKey(h.key())) continue;
            long[] cs = live(h.key(), convs);
            if (cs == null) continue;
            StoredDocument d = fts.getDocument(h.key());
            if (d == null) continue;
            consider(found, h.key(), cs, d.text(), d.meta(), q);
        }
        if (cands.size() >= CANDIDATES || asciiAlnum(q)) {
            for (Entry e : fts.rangeEntries(RangeOptions.prefix(FTS).meta(true))) {
                String key = e.keyString();
                if (found.containsKey(key)) continue;
                long[] cs = live(key, convs);
                if (cs != null) consider(found, key, cs, e.valueString(), e.meta(), q);
            }
        }
        List<Found> order = new ArrayList<>(found.values());
        order.sort(Comparator.comparingLong((Found f) -> convs.get(f.conv).path("updated").asLong()).reversed()
                .thenComparing(Comparator.comparingInt(Found::seq).reversed()));
        List<SearchHit> out = new ArrayList<>();
        for (Found f : order) {
            out.add(new SearchHit(f.conv, f.seq, f.role, f.snippet, convs.get(f.conv).path("title").asString("")));
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    /** 索引项对应的消息还在不在（对话在、seq 在条数之内）；在 ⇒ {conv, seq}。 */
    private static long[] live(String key, Map<Long, ObjectNode> convs) {
        long[] cs = parseSeqKey(key);
        if (cs == null) return null;
        ObjectNode c = convs.get(cs[0]);
        return c != null && cs[1] < c.path("messages").asInt() ? cs : null;
    }

    private static void consider(Map<String, Found> found, String key, long[] cs, String text, byte[] meta, String q) {
        if (text == null) return;
        int at = text.toLowerCase(Locale.ROOT).indexOf(q);
        if (at < 0) return;
        Object role = meta == null || meta.length == 0 ? null : Meta.lookup(meta, "role");
        found.put(key, new Found(cs[0], (int) cs[1], role == null ? "user" : role.toString(),
                InMemorySessionStore.snippet(text, at, q.length())));
    }

    /** 含 ASCII 字母数字 ⇒ 可能是词内子串，索引（拉丁按整词切）给不全。 */
    static boolean asciiAlnum(String q) {
        for (int i = 0; i < q.length(); i++) {
            char ch = q.charAt(i);
            if (ch < 128 && Character.isLetterOrDigit(ch)) return true;
        }
        return false;
    }

    @Override
    public synchronized void close() {
        try { fts.close(); } finally { kv.close(); }
    }
}
