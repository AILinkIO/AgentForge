package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暂存层：一个 {@link Vfs}，写进来的东西先攒着，整轮结束一次过目、再提交或丢弃（对应 coxswain {@code tools/staging.ss}）。
 *
 * <p>读己之所写：攒着的文件读回来是攒的那份 —— 读到盘上的旧内容会让模型以为编辑没生效，再改一次，叠成重复代码。
 * 只有 stageable 的工具（write / edit / replace-form / apply-patch）走这里；删除、run-command 攒不住。
 *
 * <p>会话层用法：
 * <pre>{@code
 * Staging st = Staging.begin(ws);   // 这一轮开始：装到工作区上
 * … 跑工具 …
 * for (Staging.Change c : st.pending()) { 给人看 c.before() → c.after() 的 diff }
 * st.commit(null) / st.discard(null);   // null = 全部；也可以按路径逐个
 * Staging.end(ws);                      // 卸下，退回原来那层
 * }</pre>
 */
public final class Staging implements Vfs {

    /**
     * 一处待确认的改动。{@code before} 是第一次攒它时下面那层的内容（新文件为 ""），之后不再更新 ——
     * 用户要看的是"这一轮把文件从什么样改成了什么样"，不是最后一次调用改了什么。
     */
    public static final class Change {
        private final String path;
        private final String before;
        private final FileText baseFt;
        private String after;
        private FileText.Eol eol;
        private FileText.Coding coding;
        private boolean bom;

        Change(String path, String before, FileText baseFt, String after, FileText.Eol eol, FileText.Coding coding, boolean bom) {
            this.path = path;
            this.before = before;
            this.baseFt = baseFt;
            this.after = after;
            this.eol = eol;
            this.coding = coding;
            this.bom = bom;
        }

        public String path() { return path; }

        public String before() { return before; }

        public synchronized String after() { return after; }

        /** 第一次攒它时这个文件是否已经存在（false = 新建）。 */
        public boolean existed() { return baseFt != null; }

        synchronized FileText toFileText() {
            return new FileText(path, after, eol, coding, bom, null, baseFt == null ? 0 : baseFt.stamp());
        }
    }

    private final Vfs base;
    private final Workspace ws;
    private final Map<String, Change> table = new LinkedHashMap<>();   // 有序：用户看到的顺序是改动发生的顺序

    public Staging(Vfs base) { this(base, null); }

    public Staging(Vfs base, Workspace ws) {
        this.base = base == null ? Vfs.DISK : base;
        this.ws = ws;
    }

    /** 在工作区上装一层暂存（叠在当前 vfs 之上），返回它。 */
    public static Staging begin(Workspace ws) {
        Vfs cur = Vfs.of(ws);
        if (cur instanceof Staging s) return s;
        Staging s = new Staging(cur, ws);
        Vfs.install(ws, s);
        return s;
    }

    /** 当前装着的暂存层，没有回 null。 */
    public static Staging current(Workspace ws) { return Vfs.of(ws) instanceof Staging s ? s : null; }

    /** 卸下暂存层，退回下面那层（还没提交的改动随之丢弃 —— 先 commit 再 end）。 */
    public static void end(Workspace ws) {
        if (Vfs.of(ws) instanceof Staging s) Vfs.install(ws, s.base);
    }

    public Vfs base() { return base; }

    @Override
    public FileText read(String path) throws IOException {
        Change c;
        synchronized (this) { c = table.get(path); }
        return c != null ? c.toFileText() : base.read(path);
    }

    @Override
    public boolean exists(String path) {
        synchronized (this) { if (table.containsKey(path)) return true; }
        return base.exists(path);
    }

    @Override
    public void write(String path, String text, FileText.Eol eol, FileText.Coding coding, boolean bom) {
        synchronized (this) {
            Change c = table.get(path);
            if (c != null) {
                synchronized (c) {
                    c.after = text;
                    c.eol = eol;
                    c.coding = coding;
                    c.bom = bom;
                }
                return;
            }
            FileText ft = null;
            if (base.exists(path)) {
                try { ft = base.read(path); } catch (IOException | RuntimeException ignored) { }
            }
            table.put(path, new Change(path, ft == null ? "" : ft.text(), ft, text, eol, coding, bom));
        }
    }

    /** 按发生顺序。 */
    public synchronized List<Change> pending() { return new ArrayList<>(table.values()); }

    public synchronized int count() { return table.size(); }

    public synchronized boolean isEmpty() { return table.isEmpty(); }

    /**
     * 落盘（写到下面那层）。path 为 null = 全部。返回真正写下去了几个。写完就从表里去掉 ——
     * 留着的话下一轮会把已经确认过的改动再算一遍。
     */
    public int commit(String path) {
        int n = 0;
        for (Change c : pending()) {
            if (path != null && !path.equals(c.path)) continue;
            FileText ft = c.toFileText();
            Vfs.writeOn(base, ws, c.path, ft.text(), ft.eol(), ft.coding(), ft.bom());
            synchronized (this) { table.remove(c.path); }
            n++;
        }
        return n;
    }

    /** 丢弃。path 为 null = 全部。返回丢了几个。 */
    public synchronized int discard(String path) {
        if (path == null) {
            int n = table.size();
            table.clear();
            return n;
        }
        return table.remove(path) != null ? 1 : 0;
    }
}
