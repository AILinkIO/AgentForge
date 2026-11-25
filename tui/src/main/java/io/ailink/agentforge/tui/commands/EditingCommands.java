package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Keymap;
import io.ailink.agentforge.tui.UserError;
import io.ailink.agentforge.tui.text.Width;

/**
 * 输入框（prompt）与 minibuffer 的编辑命令：移动、插入删除、kill / yank、mark、undo、大小写、前缀参数、键盘宏。
 * 命令名照 Emacs（coxswain {@code coxn.commands.basic} 的子集）；在只读的 transcript 里移动类命令照样能用。
 */
public final class EditingCommands {
    private EditingCommands() {}

    static boolean wordChar(int c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** 从 pos 往前过一个词：先跳过非词字符，再跳过词字符。 */
    public static int forwardWordPos(Buffer b, int pos) {
        int n = b.length(), p = pos;
        while (p < n && !wordChar(b.charAt(p))) p++;
        while (p < n && wordChar(b.charAt(p))) p++;
        return p;
    }

    public static int backwardWordPos(Buffer b, int pos) {
        int p = pos;
        while (p > 0 && !wordChar(b.charAt(p - 1))) p--;
        while (p > 0 && wordChar(b.charAt(p - 1))) p--;
        return p;
    }

    private static int bol(Editor ed, int p) { Buffer b = ed.currentBuffer(); return b.text().lineStart(b.text().lineOf(p)); }
    private static int eol(Editor ed, int p) { Buffer b = ed.currentBuffer(); return b.text().lineEnd(b.text().lineOf(p)); }

    private static void chars(Editor ed, int n) {
        Buffer b = ed.currentBuffer();
        int p = ed.point();
        for (; n > 0; n--) { if (p >= b.length()) { ed.goTo(p); throw new UserError("End of buffer"); } p = Width.nextPos(b.text(), p); }
        for (; n < 0; n++) { if (p <= 0) { ed.goTo(p); throw new UserError("Beginning of buffer"); } p = Width.prevPos(b.text(), p); }
        ed.goTo(p);
    }

    public static void install(Editor ed) {
        // ── 移动 ──
        ed.defineCommand("forward-char", "往前一个字（C-f）。", e -> chars(e, e.countArg()));
        ed.defineCommand("backward-char", "往后一个字（C-b）。", e -> chars(e, -e.countArg()));
        ed.defineCommand("next-line", "下一行（C-n），保持目标列；按屏幕行走。", e -> {
            if (e.lineMove(e.countArg())) throw new UserError("End of buffer");
        });
        ed.defineCommand("previous-line", "上一行（C-p）。", e -> {
            if (e.lineMove(-e.countArg())) throw new UserError("Beginning of buffer");
        });
        ed.defineCommand("move-beginning-of-line", "行首（C-a）。", e -> e.goTo(bol(e, e.point())));
        ed.defineCommand("move-end-of-line", "行尾（C-e）。", e -> e.goTo(eol(e, e.point())));
        ed.defineCommand("back-to-indentation", "跳到本行第一个非空白字。", e -> {
            Buffer b = e.currentBuffer();
            int p = bol(e, e.point()), end = eol(e, e.point());
            while (p < end && Character.isWhitespace(b.charAt(p))) p++;
            e.goTo(p);
        });
        ed.defineCommand("forward-word", "往前一个词（M-f）。", e -> {
            int p = e.point();
            for (int i = 0; i < e.countArg(); i++) p = forwardWordPos(e.currentBuffer(), p);
            e.goTo(p);
        });
        ed.defineCommand("backward-word", "往后一个词（M-b）。", e -> {
            int p = e.point();
            for (int i = 0; i < e.countArg(); i++) p = backwardWordPos(e.currentBuffer(), p);
            e.goTo(p);
        });
        ed.defineCommand("beginning-of-buffer", "buffer 开头（M-<），原位置记进 mark。", e -> {
            e.currentBuffer().setMark(e.point(), false);
            e.goTo(0);
        });
        ed.defineCommand("end-of-buffer", "buffer 末尾（M->），原位置记进 mark；跟随末尾的窗口回到末尾就接着跟。", e -> {
            e.currentBuffer().setMark(e.point(), false);
            e.goTo(e.currentBuffer().length());
        });
        ed.defineCommand("scroll-up-command", "往下翻一屏（C-v）。", e ->
                e.scrollWindow(e.currentWindow(), e.prefixArg() != null ? e.countArg() : e.pageHeight()));
        ed.defineCommand("scroll-down-command", "往上翻一屏（M-v）。", e ->
                e.scrollWindow(e.currentWindow(), -(e.prefixArg() != null ? e.countArg() : e.pageHeight())));
        ed.defineCommand("recenter-top-bottom", "C-l：point 行依次放到中间 / 顶 / 底。", new io.ailink.agentforge.tui.Command() {
            int state;
            @Override public void run(Editor e) {
                state = "recenter-top-bottom".equals(e.lastCommand()) ? (state + 1) % 3 : 0;
                int h = e.windowHeight(e.currentWindow());
                e.recenter(state == 0 ? h / 2 : state == 1 ? 0 : h - 1);
            }
        });

        // ── 插入 / 删除 ──
        ed.defineCommand("self-insert-command", "插入刚按下的字。", e -> {
            String k = e.thisKey();
            if (k == null || !Keymap.printable(k)) return;
            String ch = Keymap.keyChar(k);
            int n = Math.max(1, e.countArg());
            e.insert(ch.repeat(n));
            e.afterSelfInsert(ch);
        });
        ed.defineCommand("newline", "换行（RET）。", e -> e.insert("\n".repeat(Math.max(1, e.countArg()))));
        ed.defineCommand("open-line", "C-o：在 point 后面插换行，point 不动。", e -> {
            int p = e.point();
            e.insert("\n".repeat(Math.max(1, e.countArg())));
            e.goTo(p);
        });
        ed.defineCommand("delete-char", "删 point 后面的字（C-d）。", e -> {
            Buffer b = e.currentBuffer();
            int p = e.point(), q = p;
            for (int i = 0; i < e.countArg(); i++) { if (q >= b.length()) throw new UserError("End of buffer"); q = Width.nextPos(b.text(), q); }
            e.deleteRegion(p, q);
        });
        ed.defineCommand("delete-forward-char", "<delete>：有活动区域删区域，否则删后一个字。", e -> {
            if (e.regionActive()) { int[] r = e.region(); e.deleteRegion(r[0], r[1]); }
            else e.runCommand("delete-char", null);
        });
        ed.defineCommand("delete-backward-char", "DEL：有活动区域删区域，否则删前一个字。", e -> {
            if (e.regionActive()) { int[] r = e.region(); e.deleteRegion(r[0], r[1]); return; }
            Buffer b = e.currentBuffer();
            int p = e.point(), q = p;
            for (int i = 0; i < e.countArg(); i++) { if (q <= 0) throw new UserError("Beginning of buffer"); q = Width.prevPos(b.text(), q); }
            e.deleteRegion(q, p);
        });
        ed.defineCommand("delete-horizontal-space", "M-\\：删 point 两边的空白。", e -> {
            Buffer b = e.currentBuffer();
            int a = e.point(), z = a;
            while (a > 0 && (b.charAt(a - 1) == ' ' || b.charAt(a - 1) == '\t')) a--;
            while (z < b.length() && (b.charAt(z) == ' ' || b.charAt(z) == '\t')) z++;
            e.deleteRegion(a, z);
        });
        ed.defineCommand("just-one-space", "M-SPC：point 两边的空白换成一个空格。", e -> {
            Buffer b = e.currentBuffer();
            int a = e.point(), z = a;
            while (a > 0 && (b.charAt(a - 1) == ' ' || b.charAt(a - 1) == '\t')) a--;
            while (z < b.length() && (b.charAt(z) == ' ' || b.charAt(z) == '\t')) z++;
            e.replaceRegion(a, z, " ");
        });
        ed.defineCommand("transpose-chars", "C-t：交换 point 前后两个字。", e -> {
            Buffer b = e.currentBuffer();
            int p = e.point();
            if (p == 0) throw new UserError("Beginning of buffer");
            if (p >= b.length() || b.charAt(p) == '\n') p--;
            if (p == 0) throw new UserError("Beginning of buffer");
            String s = b.substring(p - 1, p + 1);
            e.replaceRegion(p - 1, p + 1, "" + s.charAt(1) + s.charAt(0));
        });
        ed.defineCommand("upcase-word", "M-u", e -> caseWord(e, 0));
        ed.defineCommand("downcase-word", "M-l", e -> caseWord(e, 1));
        ed.defineCommand("capitalize-word", "M-c", e -> caseWord(e, 2));
        ed.defineCommand("quoted-insert", "C-q：把下一个键原样插入。", e -> e.readKey(null, k -> {
            String s = k.equals("TAB") ? "\t" : k.equals("RET") ? "\r" : k.equals("SPC") ? " "
                    : k.matches("C-[a-z]") ? String.valueOf((char) (k.charAt(2) - 96)) : Keymap.printable(k) ? k : null;
            if (s != null) e.insert(s);
        }));

        // ── mark / kill / yank ──
        ed.defineCommand("set-mark-command", "C-SPC：设 mark 并激活区域；连按两次取消。", e -> {
            Buffer b = e.currentBuffer();
            if ("set-mark-command".equals(e.lastCommand()) && b.markActive() && b.mark() == e.point()) {
                b.deactivateMark();
                e.message("Mark deactivated");
            } else {
                e.setMark(e.point());
                e.message("Mark set");
            }
        });
        ed.defineCommand("exchange-point-and-mark", "point 和 mark 对调。", e -> {
            Integer m = e.currentBuffer().mark();
            if (m == null) throw new UserError("No mark set in this buffer");
            int p = e.point();
            e.goTo(m);
            e.setMark(p);
        });
        ed.defineCommand("mark-whole-buffer", "选中整个 buffer。", e -> {
            e.setMark(e.currentBuffer().length());
            e.goTo(0);
            e.message("Mark set");
        });
        ed.defineCommand("kill-region", "C-w：剪掉区域。", e -> {
            int[] r = e.region();
            e.killNew(e.currentBuffer().substring(r[0], r[1]), e.point() < r[1]);
            e.deleteRegion(r[0], r[1]);
            e.currentBuffer().deactivateMark();
        });
        ed.defineCommand("kill-ring-save", "M-w：复制区域（只读的 transcript 里也行）。", e -> {
            int[] r = e.region();
            e.killNew(e.currentBuffer().substring(r[0], r[1]));
            e.currentBuffer().deactivateMark();
        });
        ed.defineCommand("kill-line", "C-k：剪到行尾；在行尾剪掉换行；带数字剪 N 行。", e -> {
            Buffer b = e.currentBuffer();
            int p = e.point(), end;
            if (e.prefixArg() != null) {
                int row = b.text().lineOf(p) + e.countArg();
                end = row >= b.lineCount() ? b.length() : b.text().lineStart(row);
            } else {
                int l = eol(e, p);
                end = b.substring(p, l).isBlank() ? Math.min(b.length(), l + 1) : l;
            }
            if (p == end) throw new UserError("End of buffer");
            e.killNew(b.substring(p, end));
            e.deleteRegion(p, end);
        });
        ed.defineCommand("kill-whole-line", "C-S-DEL：剪掉整行。", e -> {
            Buffer b = e.currentBuffer();
            int row = b.text().lineOf(e.point());
            int a = b.text().lineStart(row), z = Math.min(b.length(), b.text().lineEnd(row) + 1);
            e.killNew(b.substring(a, z));
            e.deleteRegion(a, z);
        });
        ed.defineCommand("kill-word", "M-d", e -> {
            int p = e.point(), q = p;
            for (int i = 0; i < Math.max(1, e.countArg()); i++) q = forwardWordPos(e.currentBuffer(), q);
            e.killNew(e.currentBuffer().substring(p, q));
            e.deleteRegion(p, q);
        });
        ed.defineCommand("backward-kill-word", "M-DEL", e -> {
            int p = e.point(), q = p;
            for (int i = 0; i < Math.max(1, e.countArg()); i++) q = backwardWordPos(e.currentBuffer(), q);
            e.killNew(e.currentBuffer().substring(q, p), true);
            e.deleteRegion(q, p);
        });
        ed.defineCommand("yank", "C-y：插入最近一次剪下的文本，mark 留在开头。", e -> {
            String s = e.currentKill();
            if (s == null) throw new UserError("Kill ring is empty");
            int p = e.point();
            e.insert(s);
            e.currentBuffer().setMark(p, false);
            e.setYankIndex(0);
        });
        ed.defineCommand("yank-pop", "M-y：紧跟 C-y 时换成更早的一条。", e -> {
            if (!"yank".equals(e.lastCommand()) && !"yank-pop".equals(e.lastCommand()))
                throw new UserError("Previous command was not a yank");
            int i = e.yankIndex() + 1;
            String s = e.kill(i);
            Integer m = e.currentBuffer().mark();
            int p = e.point();
            e.replaceRegion(m, p, s);
            e.currentBuffer().setMark(Math.min(m, p), false);
            e.setYankIndex(i);
        });

        // ── undo ──
        ed.defineCommand("undo", "C-/：撤销一组改动。", e -> {
            if (!e.undo()) throw new UserError("No further undo information");
            e.message("Undo");
        });
        ed.defineCommand("undo-redo", "C-?：重做被撤销的。", e -> {
            if (!e.redo()) throw new UserError("No further redo information");
            e.message("Redo");
        });

        // ── 前缀参数 / 杂项 ──
        ed.defineCommand("universal-argument", "C-u：前缀参数 ×4。", e -> {
            Object p = e.prefixArg();
            e.setNextPrefixArg(new int[]{p instanceof int[] a ? a[0] * 4 : 4});
        });
        ed.defineCommand("digit-argument", "M-<数字>：数字前缀参数。", e -> {
            String k = e.thisKey();
            int d = k.charAt(k.length() - 1) - '0';
            Object p = e.prefixArg();
            e.setNextPrefixArg(p instanceof Integer i ? i * 10 + (i < 0 ? -d : d) : p == Editor.MINUS ? -d : d);
        });
        ed.defineCommand("negative-argument", "M--", e -> e.setNextPrefixArg(Editor.MINUS));
        ed.defineCommand("keyboard-quit", "C-g：取消。", e -> {
            e.currentBuffer().deactivateMark();
            e.closeCompletion();
            e.message("Quit");
        });
        ed.defineCommand("keyboard-escape-quit", "ESC ESC ESC", e -> {
            if (e.minibufferActive()) e.minibufferAbort();
            else if (e.regionActive()) e.currentBuffer().deactivateMark();
            else if (!e.popups().isEmpty()) e.closePopup(e.popups().getLast());
        });
        ed.defineCommand("read-only-mode", "C-x C-q：切换只读。", e -> {
            Buffer b = e.currentBuffer();
            b.setReadOnly(!b.readOnly());
        });
        ed.defineCommand("toggle-truncate-lines", "长行折行 / 截断。", e -> {
            Buffer b = e.currentBuffer();
            b.put("truncate-lines", !b.flag("truncate-lines"));
            e.message(b.flag("truncate-lines") ? "Truncate long lines enabled" : "Truncate long lines disabled");
        });
        ed.defineCommand("visual-line-mode", "按词折行（visual-line-mode）开关。", e -> {
            Buffer b = e.currentBuffer();
            b.put("word-wrap", !b.flag("word-wrap"));
        });
        ed.defineCommand("display-line-numbers-mode", "行号开关。", e -> {
            Buffer b = e.currentBuffer();
            b.put("display-line-numbers", !b.flag("display-line-numbers"));
        });
        ed.defineCommand("completion-at-point", "M-TAB：补全 point 前面那段（弹框）。", e -> e.startCompletion(false));

        // ── 键盘宏 / repeat ──
        ed.defineCommand("kmacro-start-macro", "开始录键盘宏。", Editor::startMacro);
        ed.defineCommand("kmacro-end-macro", "结束录键盘宏。", Editor::endMacro);
        ed.defineCommand("kmacro-end-and-call-macro", "结束录制（如果在录）并执行宏。", e -> e.callMacro(e.countArg()));
        ed.defineCommand("repeat", "重复上一条命令。", e -> {
            String c = e.repeatableCommand();
            if (c == null) throw new UserError("No previous command to repeat");
            e.runAfterCommand(c, e.prefixArg());
        });

        // ── minibuffer ──
        ed.defineInternalCommand("minibuffer-complete-and-exit", "RET：有候选取选中的那个，否则按原样提交。", e -> {
            var r = e.activeMinibufferRequest();
            if (r == null) return;
            String c = e.minibufferSelectedCandidate();
            if (c != null) e.minibufferFinish(c);
            else if (e.minibufferCandidates() != null && e.activeMinibufferRequest().isRequireMatch()) e.message("[No match]");
            else e.minibufferFinish(e.minibufferInput());
        });
        ed.defineInternalCommand("exit-minibuffer", "M-j / C-j：按输入原样提交。", e -> e.minibufferFinish(e.minibufferInput()));
        ed.defineInternalCommand("minibuffer-complete", "TAB：只有一个候选就补全它，否则补到公共前缀。", e -> {
            var items = e.minibufferCandidates();
            if (items == null) return;
            if (items.isEmpty()) { e.message("[No match]"); return; }
            String in = e.minibufferInput();
            if (items.size() == 1) { e.setMinibufferInput(items.getFirst()); return; }
            String cp = items.getFirst();
            for (String s : items) { int n = 0; while (n < cp.length() && n < s.length() && cp.charAt(n) == s.charAt(n)) n++; cp = cp.substring(0, n); }
            e.setMinibufferInput(cp.length() > in.length() && cp.toLowerCase().startsWith(in.toLowerCase()) ? cp : e.minibufferSelectedCandidate());
        });
        ed.defineInternalCommand("next-completion", "选下一个候选。", e -> e.minibufferSelect(e.countArg()));
        ed.defineInternalCommand("previous-completion", "选上一个候选。", e -> e.minibufferSelect(-e.countArg()));
        ed.defineInternalCommand("next-history-element", "M-n：下一条历史。", e -> e.minibufferHistoryStep(-1));
        ed.defineInternalCommand("previous-history-element", "M-p：上一条历史。", e -> e.minibufferHistoryStep(1));
        ed.defineInternalCommand("abort-minibuffers", "C-g（minibuffer 里）", Editor::minibufferAbort);
    }

    private static void caseWord(Editor e, int mode) {
        Buffer b = e.currentBuffer();
        int p = e.point(), q = p;
        for (int i = 0; i < Math.max(1, e.countArg()); i++) q = forwardWordPos(b, q);
        String s = b.substring(p, q);
        String r = switch (mode) {
            case 0 -> s.toUpperCase();
            case 1 -> s.toLowerCase();
            default -> capitalize(s);
        };
        e.replaceRegion(p, q, r);
    }

    /** 每个词首字母大写、其余小写（数字开头的词不动字母）。 */
    public static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (wordChar(c)) {
                sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = false;
            } else {
                sb.append(c);
                start = true;
            }
        }
        return sb.toString();
    }
}
