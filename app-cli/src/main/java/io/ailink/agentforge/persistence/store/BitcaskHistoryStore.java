package io.ailink.agentforge.persistence.store;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.entity.DailySummaryEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 聊天历史与每日总结的 bitcask 存储（替代原来的 JPA + H2）。
 *
 * <p>键布局（全部 UTF-8 字符串，按字节序即时间序）：
 * <ul>
 *   <li>{@code msg/<20 位零填充 id>} → {@code {"id","role","content","createdAt"}}</li>
 *   <li>{@code summary/<yyyy-MM-dd>} → {@code {"summaryDate","summary","messageCount","createdAt","updatedAt"}}</li>
 *   <li>{@code seq/msg} → 最近分配的消息 id</li>
 * </ul>
 * id 单调递增且 createdAt 随之递增，所以"按时间范围查"就是对 {@code msg/} 前缀做一次范围扫描后
 * 按 createdAt 过滤 —— 不需要二级索引。
 */
public class BitcaskHistoryStore {
    private static final String MSG = "msg/";
    private static final String SUMMARY = "summary/";
    private static final String SEQ = "seq/msg";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Bitcask db;
    private long seq;

    public BitcaskHistoryStore(Bitcask db) {
        this.db = db;
        String s = db.getString(SEQ);
        this.seq = s == null ? 0 : Long.parseLong(s.trim());
    }

    public synchronized ChatMessageEntity save(ChatMessageEntity m) {
        if (m.getId() == null) m.setId(++seq);
        if (m.getCreatedAt() == null) m.setCreatedAt(LocalDateTime.now());
        ObjectNode o = JSON.createObjectNode();
        o.put("id", m.getId());
        o.put("role", m.getRole());
        o.put("content", m.getContent());
        o.put("createdAt", m.getCreatedAt().toString());
        db.put(msgKey(m.getId()), o.toString());
        db.put(SEQ, Long.toString(seq));
        return m;
    }

    /** 全部消息，按时间正序。 */
    public List<ChatMessageEntity> findAll() {
        List<ChatMessageEntity> out = new ArrayList<>();
        for (Entry e : db.rangeEntries(RangeOptions.prefix(MSG))) out.add(toMessage(JSON.readTree(e.valueString())));
        return out;
    }

    /** 最近 limit 条，按时间正序。 */
    public List<ChatMessageEntity> findRecent(int limit) {
        List<ChatMessageEntity> all = findAll();
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    /** [from, to] 闭区间内的消息，按时间正序。 */
    public List<ChatMessageEntity> findBetween(LocalDateTime from, LocalDateTime to) {
        List<ChatMessageEntity> out = new ArrayList<>();
        for (ChatMessageEntity m : findAll()) {
            if (!m.getCreatedAt().isBefore(from) && !m.getCreatedAt().isAfter(to)) out.add(m);
        }
        return out;
    }

    public long count() { return db.rangeEntries(RangeOptions.prefix(MSG)).size(); }

    public DailySummaryEntity saveSummary(DailySummaryEntity s) {
        ObjectNode o = JSON.createObjectNode();
        o.put("summaryDate", s.getSummaryDate().toString());
        o.put("summary", s.getSummary());
        o.put("messageCount", s.getMessageCount());
        o.put("createdAt", s.getCreatedAt().toString());
        o.put("updatedAt", s.getUpdatedAt().toString());
        db.put(SUMMARY + s.getSummaryDate(), o.toString());
        return s;
    }

    public Optional<DailySummaryEntity> findSummary(LocalDate date) {
        String v = db.getString(SUMMARY + date);
        return v == null ? Optional.empty() : Optional.of(toSummary(JSON.readTree(v)));
    }

    /** 全部每日总结，按日期倒序。 */
    public List<DailySummaryEntity> findAllSummaries() {
        List<DailySummaryEntity> out = new ArrayList<>();
        for (Entry e : db.rangeEntries(RangeOptions.prefix(SUMMARY))) out.add(toSummary(JSON.readTree(e.valueString())));
        return out.reversed();
    }

    private static String msgKey(long id) { return MSG + String.format("%020d", id); }

    private static ChatMessageEntity toMessage(JsonNode n) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(n.path("id").asLong());
        m.setRole(n.path("role").asString());
        m.setContent(n.path("content").asString());
        m.setCreatedAt(LocalDateTime.parse(n.path("createdAt").asString()));
        return m;
    }

    private static DailySummaryEntity toSummary(JsonNode n) {
        DailySummaryEntity s = new DailySummaryEntity();
        s.setSummaryDate(LocalDate.parse(n.path("summaryDate").asString()));
        s.setSummary(n.path("summary").asString());
        s.setMessageCount(n.path("messageCount").asInt());
        s.setCreatedAt(LocalDateTime.parse(n.path("createdAt").asString()));
        s.setUpdatedAt(LocalDateTime.parse(n.path("updatedAt").asString()));
        return s;
    }
}
