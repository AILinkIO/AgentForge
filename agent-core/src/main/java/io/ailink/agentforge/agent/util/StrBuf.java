package io.ailink.agentforge.agent.util;

/**
 * 能读任意多次的可增长字符缓冲（coxswain {@code (coxn agent strbuf)}）。
 *
 * <p>流式每个 delta 追加一次、UI 每帧把整段读出来：用 {@code +} 攒是平方级。这里追加摊还 O(1)，
 * 读时才拼一次并缓存，内容没变就直接回缓存。四个操作都上锁 —— 写在 agent 线程、读在 UI 线程。
 *
 * <p>{@link #epoch()} 是世代号：每 {@link #reset()} 一次 +1，显示层据此判断"还是不是同一段"。
 */
public final class StrBuf {
    private char[] chars = new char[256];
    private int len;
    private String cache = "";
    private long epoch;

    /** 清空但留着容量（下一轮回答多半一样长）；世代号 +1。 */
    public synchronized void reset() {
        len = 0;
        cache = "";
        epoch++;
    }

    public synchronized void add(CharSequence text) {
        int n = text.length();
        if (n == 0) return;
        int need = len + n;
        if (need > chars.length) {
            int cap = Math.max(256, chars.length);
            while (cap < need) cap *= 2;
            char[] bigger = new char[cap];
            System.arraycopy(chars, 0, bigger, 0, len);
            chars = bigger;
        }
        for (int i = 0; i < n; i++) chars[len + i] = text.charAt(i);
        len = need;
        cache = null;
    }

    public synchronized String text() {
        if (cache == null) cache = new String(chars, 0, len);
        return cache;
    }

    public synchronized int length() { return len; }

    public synchronized long epoch() { return epoch; }

    @Override
    public String toString() { return text(); }
}
