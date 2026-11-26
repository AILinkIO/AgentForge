package io.ailink.agentforge.agent.store;

import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.model.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内存里的 {@link SessionStore}（测试、无盘宿主用；进程退出即没）。消息按 JSON 存一份 —— 与持久化实现走同一条
 * 编解码，读回来的就是"从盘上读回来"的那个样子（编解码丢了什么，这里的测试就能看见）。
 */
public final class InMemorySessionStore implements SessionStore {

    private static final class Conv {
        final long id;
        String title;
        final long created;
        long updated;
        final Long parent;
        final String sid;
        String project;
        String root;
        final List<String> messages = new ArrayList<>();
        final List<String> texts = new ArrayList<>();      // 全文索引那份（每条消息的可搜文本）
        final List<String> roles = new ArrayList<>();
        final Map<Integer, Compaction.Pointer> compactions = new LinkedHashMap<>();

        Conv(long id, String title, long created, Long parent, String sid) {
            this.id = id;
            this.title = title;
            this.created = created;
            this.updated = created;
            this.parent = parent;
            this.sid = sid;
        }

        Conversation view() { return new Conversation(id, title, created, updated, parent, sid, messages.size(), project, root); }
    }

    private final Map<Long, Conv> convs = new LinkedHashMap<>();
    private long nextId = 1;
    private long lastNow;

    /** 单调的毫秒时钟（同一毫秒里连建两段时，"最近更新的在前"仍有确定的答案）。 */
    private long now() {
        long t = System.currentTimeMillis();
        lastNow = Math.max(t, lastNow + 1);
        return lastNow;
    }

    private Conv need(long conv) {
        Conv c = convs.get(conv);
        if (c == null) throw new IllegalArgumentException("没有这段对话：" + conv);
        return c;
    }

    @Override
    public synchronized long newConversation(String title, Long parent, String sid) {
        long id = nextId++;
        convs.put(id, new Conv(id, title == null ? "" : title, now(), parent, sid));
        return id;
    }

    @Override
    public synchronized void setTitle(long conv, String title) { need(conv).title = title == null ? "" : title; }

    @Override
    public synchronized void setPlacement(long conv, String project, String root) {
        Conv c = need(conv);
        c.project = project;
        c.root = root;
    }

    @Override
    public synchronized void deleteConversation(long conv) {
        for (Conversation ch : children(conv)) deleteConversation(ch.id());
        convs.remove(conv);
    }

    @Override
    public synchronized List<Conversation> conversations(int limit) {
        List<Conversation> out = convs.values().stream().filter(c -> c.parent == null)
                .sorted(Comparator.comparingLong((Conv c) -> c.updated).reversed()).map(Conv::view).toList();
        return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public synchronized List<Conversation> children(long conv) {
        return convs.values().stream().filter(c -> c.parent != null && c.parent == conv).map(Conv::view).toList();
    }

    @Override
    public synchronized Optional<Conversation> conversation(long conv) {
        Conv c = convs.get(conv);
        return c == null ? Optional.empty() : Optional.of(c.view());
    }

    @Override
    public synchronized void append(long conv, List<Message> msgs) {
        Conv c = need(conv);
        for (Message m : msgs) {
            c.messages.add(MessageCodec.toJson(m));
            c.texts.add(Reminder.shownText(m));
            c.roles.add(m.role().wire());
        }
        c.updated = now();
    }

    @Override
    public synchronized int sync(long conv, List<Message> history) {
        Conv c = need(conv);
        int have = c.messages.size();
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
        List<Message> out = new ArrayList<>();
        for (String s : need(conv).messages) out.add(MessageCodec.fromJson(s));
        return out;
    }

    @Override
    public synchronized List<Compaction.Pointer> compactions(long conv) { return List.copyOf(need(conv).compactions.values()); }

    @Override
    public synchronized void addCompaction(long conv, Compaction.Pointer p) { need(conv).compactions.put(p.at(), p); }

    @Override
    public synchronized int truncate(int keep) {
        List<Conversation> all = conversations(0);
        int n = 0;
        for (int i = Math.max(0, keep); i < all.size(); i++) { deleteConversation(all.get(i).id()); n++; }
        return n;
    }

    @Override
    public synchronized List<SearchHit> search(String query, int limit) {
        String q = SessionStore.checkQuery(query).toLowerCase(Locale.ROOT);
        List<SearchHit> out = new ArrayList<>();
        List<Conv> order = new ArrayList<>(convs.values());
        order.sort(Comparator.comparingLong((Conv c) -> c.updated).reversed());
        for (Conv c : order) {
            for (int i = c.texts.size() - 1; i >= 0; i--) {
                String t = c.texts.get(i);
                int at = t.toLowerCase(Locale.ROOT).indexOf(q);
                if (at < 0) continue;
                out.add(new SearchHit(c.id, i, c.roles.get(i), snippet(t, at, q.length()), c.title));
                if (limit > 0 && out.size() >= limit) return out;
            }
        }
        return out;
    }

    static String snippet(String t, int at, int len) {
        int from = Math.max(0, at - 30), to = Math.min(t.length(), at + len + 30);
        return (from > 0 ? "…" : "") + t.substring(from, to).replace('\n', ' ') + (to < t.length() ? "…" : "");
    }
}
