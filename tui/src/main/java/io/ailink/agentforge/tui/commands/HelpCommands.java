package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.I18n;
import io.ailink.agentforge.tui.Keymap;
import io.ailink.agentforge.tui.MinibufferRequest;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.Window;

import java.util.Comparator;
import java.util.List;

/** M-x 与帮助（describe-key / describe-function / describe-bindings / where-is，coxswain {@code coxn.commands.help}）。 */
public final class HelpCommands {
    private HelpCommands() {}

    public static void install(Editor ed) {
        ed.defineCommand("execute-extended-command", "M-x：按名字执行命令（helm 样式：模糊过滤、前缀优先，右边是键）。", e -> {
            Object pa = e.prefixArg();
            String prefix = pa instanceof int[] ? "C-u " : pa instanceof Integer i ? i + " " : "";
            e.readFromMinibuffer(new MinibufferRequest(prefix + "M-x ")
                    .candidates(e.commandNames())
                    .annotate((x, item) -> keyFor(x, item))
                    .requireMatch(true).history("command")
                    .onDone(name -> {
                        if (e.command(name) != null) e.runAfterCommand(name, pa);
                        else e.message("[No match]");
                    }));
        });
        ed.defineCommand("describe-key", "<f1> k：按一个键序列，显示它跑哪条命令。", e -> {
            e.startDescribeKey("full");
            e.message("Describe the following key, mouse click, or menu item: ");
        });
        ed.defineCommand("describe-key-briefly", "<f1> c", e -> {
            e.startDescribeKey("brief");
            e.message("Describe the following key briefly: ");
        });
        ed.defineCommand("describe-function", "<f1> f：命令的说明。", e ->
                e.readFromMinibuffer(new MinibufferRequest("Describe function: ").candidates(e.commandNames())
                        .requireMatch(true).history("command").onDone(n -> showHelp(e, describeCommand(e, n)))));
        ed.defineCommand("describe-bindings", "<f1> b：列出当前生效的全部键。", e -> {
            StringBuilder sb = new StringBuilder();
            for (Keymap km : e.activeKeymaps()) {
                List<String[]> rows = km.flatten();
                if (rows.isEmpty()) continue;
                rows.sort(Comparator.comparing(r -> r[0]));
                int w = 8;
                for (String[] r : rows) w = Math.max(w, r[0].length());
                sb.append(km.name() == null ? "?" : km.name()).append(" bindings:\n\n");
                for (String[] r : rows) sb.append(String.format("%-" + w + "s  %s%n", r[0], r[1]));
                sb.append('\n');
            }
            showHelp(e, sb.toString());
        });
        ed.defineCommand("where-is", "<f1> w：命令绑在哪个键上。", e ->
                e.readFromMinibuffer(new MinibufferRequest("Where is command: ").candidates(e.commandNames())
                        .requireMatch(true).onDone(n -> {
                            String k = keyFor(e, n);
                            if (k != null) e.message("%s is on %s", n, k); else e.message("%s is not on any key", n);
                        })));
    }

    /** 命令在当前键表链里的第一个键（M-x 的注释）。 */
    public static String keyFor(Editor e, String cmd) {
        for (Keymap km : e.activeKeymaps()) {
            String k = km.whereIs(cmd);
            if (k != null) return k;
        }
        return null;
    }

    public static String describeCommand(Editor e, String name) {
        Editor.CommandInfo ci = e.command(name);
        String k = keyFor(e, name);
        return name + I18n.t(" is an interactive command.") + "\n\n"
                + (k != null ? I18n.format("It is bound to %s.", k) : I18n.t("It is not bound to any key.")) + "\n\n"
                + (ci == null || ci.doc() == null ? I18n.t("Not documented.") : ci.doc()) + "\n";
    }

    /** 命令循环读到完整键序列后调（describe-key 模式）。 */
    public static void describeKeyDone(Editor e, List<String> keys, Object cmd) {
        String kd = Keymap.keyDesc(keys);
        String m = e.takeDescribeKeyMode();
        if (cmd == null) { e.message("%s is undefined", kd); return; }
        String name = cmd instanceof String s ? s : "λ";
        if ("brief".equals(m)) e.message("%s runs the command %s", kd, name);
        else showHelp(e, I18n.format("%s runs the command %s", kd, name) + "\n\n" + describeCommand(e, name));
    }

    /** 在别的窗口显示 *Help*（只读，q 关掉）。 */
    public static Window showHelp(Editor e, String text) {
        Buffer h = e.getOrCreateBuffer("*Help*");
        h.setReadOnly(true);
        h.clear();
        h.append(text);
        if (h.mode() != Mode.SPECIAL) h.setMode(Mode.SPECIAL);
        h.put("word-wrap", true);
        Window w = e.displayBufferOtherWindow(h);
        w.setPoint(0);
        w.setStart(0);
        return w;
    }
}
