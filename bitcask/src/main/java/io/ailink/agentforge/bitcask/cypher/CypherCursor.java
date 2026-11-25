package io.ailink.agentforge.bitcask.cypher;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 只读语句的流式游标（{@link Cypher#cursor}）：执行是惰性的，{@link #fetch} 取多少算多少；取完自动关闭。非线程安全。
 * 已关闭的游标再取 ⇒ {@link CypherException}（exec，"cursor closed"）。
 */
public final class CypherCursor implements AutoCloseable {
    private final List<String> columns;
    private Iterator<List<Object>> rows;

    /** 一次 fetch 的结果：行与「后面还有没有」。 */
    public record Batch(List<List<Object>> rows, boolean more) {
    }

    CypherCursor(List<String> columns, Iterator<List<Object>> rows) {
        this.columns = List.copyOf(columns);
        this.rows = rows;
    }

    public List<String> columns() {
        return columns;
    }

    /** 取至多 n 行；取完自动关闭。 */
    public Batch fetch(int n) {
        if (n <= 0) throw new IllegalArgumentException("n");
        if (rows == null) throw CypherException.of(new CypherError("exec", "cursor closed", null, null, null, null));
        try {
            List<List<Object>> got = Cypher.translating(() -> {
                List<List<Object>> acc = new ArrayList<>();
                while (acc.size() < n && rows.hasNext()) acc.add(rows.next());
                return acc;
            });
            boolean more = Cypher.translating(() -> rows.hasNext());
            if (!more) rows = null;
            return new Batch(got, more);
        } catch (RuntimeException e) {
            rows = null;
            throw e;
        }
    }

    public boolean isClosed() {
        return rows == null;
    }

    /** 关闭（幂等）。 */
    @Override
    public void close() {
        rows = null;
    }
}
