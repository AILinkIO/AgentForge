package io.ailink.agentforge.tui.demo;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.CompletionItem;
import io.ailink.agentforge.tui.CompletionSource;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Fold;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Overlay;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.Split;
import io.ailink.agentforge.tui.TuiRunner;
import io.ailink.agentforge.tui.Window;
import io.ailink.agentforge.tui.commands.DocumentCommands;
import io.ailink.agentforge.tui.term.JLineTerminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 演示：一个假的 agent 工作台 —— 上面是 transcript（只读文档：分段配色、可折叠的工具输出、可点的按钮、
 * 后台线程流式追加、往上翻就不再自动滚），下面是输入框（多行、undo、kill/yank、M-p/M-n 历史、粘贴），
 * 两个窗口是一个原子组。输入 "/" 或 "@" 弹补全；M-x 命令面板；C-c / C-x 前缀（停半秒弹 which-key）；M-x load-theme 换主题。
 *
 * <p>运行：{@code ./gradlew :tui:installDist && tui/build/install/tui/bin/tui}（要真终端；C-x C-c 退出）。
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        Editor ed = build(80, 24);
        new TuiRunner(ed, new JLineTerminal()).run();
        Runtime.getRuntime().halt(0);      // JLine 的输入线程阻塞在 read 上，别等它
    }

    /** 搭好演示用的编辑器（测试也用它）。 */
    public static Editor build(int cols, int rows) {
        Editor ed = new Editor(cols, rows);
        Buffer transcript = ed.getOrCreateBuffer("*agent*");
        transcript.setMode(DocumentCommands.DOCUMENT_MODE);
        transcript.undo().setEnabled(false);
        transcript.put("word-wrap", true);
        transcript.setReadOnly(true);
        AtomicInteger turns = new AtomicInteger();
        transcript.setHeaderLine(w -> List.of(Seg.of(" AgentForge ", "mode-line-buffer-id"),
                Seg.of(" 演示会话 · 第 " + turns.get() + " 轮 · " + ed.theme() + " ", "header-line")));

        Mode promptMode = new Mode("prompt-mode", "Prompt", null);
        Buffer prompt = ed.getOrCreateBuffer("*prompt*");
        prompt.setMode(promptMode);
        prompt.put("word-wrap", true);
        ModeLine pml = new ModeLine()
                .add("hint", 10, false, c -> List.of(Seg.of(" RET 发送 · C-j 换行 · M-p/M-n 历史 · / 命令 · @ 文件 · M-x · C-c")))
                .add("pos", 20, true, c -> List.of(Seg.of(c.ed().theme() + " ")));
        prompt.setModeLine(pml);

        Window tw = ed.selectedWindow();
        ed.showBuffer(tw, transcript);
        tw.setFollowTail(true);
        Window iw = ed.splitWindow(tw, Split.Dir.BELOW, 0.8, prompt);
        ed.makeAtomic(tw, iw, "agent").setFixedSize(1, 5);
        ed.selectWindow(iw);

        welcome(transcript);

        ed.defineCommand("agent-send", "发送输入框里的内容。", e -> {
            String text = prompt.contents().strip();
            if (text.isEmpty()) return;
            List<String> h = e.history("prompt");
            h.remove(text);
            h.addFirst(text);
            prompt.setContents("");
            prompt.undo().clear();
            if (text.equals("/clear")) { transcript.clear(); return; }
            if (text.startsWith("/theme")) { e.runCommand("load-theme", null); return; }
            turns.incrementAndGet();
            userBlock(transcript, text);
            streamReply(e, transcript, text);
        });
        int[] hist = {-1};
        ed.defineCommand("prompt-history-previous", "M-p：上一条发过的输入。", e -> {
            List<String> h = e.history("prompt");
            if (h.isEmpty()) return;
            hist[0] = "prompt-history-previous".equals(e.lastCommand()) || "prompt-history-next".equals(e.lastCommand())
                    ? Math.min(h.size() - 1, hist[0] + 1) : 0;
            prompt.setContents(h.get(hist[0]));
            e.goTo(prompt.length());
        });
        ed.defineCommand("prompt-history-next", "M-n：下一条发过的输入。", e -> {
            List<String> h = e.history("prompt");
            hist[0] = Math.max(-1, hist[0] - 1);
            prompt.setContents(hist[0] < 0 ? "" : h.get(hist[0]));
            e.goTo(prompt.length());
        });
        promptMode.keymap().define("RET", "agent-send").define("C-j", "newline").define("M-RET", "newline")
                .define("M-p", "prompt-history-previous").define("M-n", "prompt-history-next");
        ed.globalMap().define("C-c a s", "agent-send").prefixName("C-c a", "agent");

        // 补全：行首的 / 命令、@ 文件提及
        List<CompletionItem> slash = List.of(
                new CompletionItem("/help", null, "帮助", "/", "列出可用的斜杠命令。", null),
                new CompletionItem("/clear", null, "清空会话", "/", null, null),
                new CompletionItem("/theme", null, "换主题", "/", "打开主题选择（同 M-x load-theme）。", null),
                new CompletionItem("/model", null, "换模型", "/", null, null));
        ed.addCompletionSource(CompletionSource.triggered('/', true, e -> e.currentBuffer() == prompt,
                p -> slash.stream().filter(i -> i.label().startsWith("/" + p) || p.isEmpty()).toList()));
        ed.addCompletionSource(CompletionSource.triggered('@', false, e -> e.currentBuffer() == prompt, Main::files));
        return ed;
    }

    static List<CompletionItem> files(String prefix) {
        List<CompletionItem> out = new ArrayList<>();
        Path root = Path.of(System.getProperty("user.dir"));
        try (Stream<Path> s = Files.walk(root, 3)) {
            s.filter(Files::isRegularFile).limit(500).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith(".") && !rel.contains("/build/") && !rel.contains("/.")) out.add(new CompletionItem("@" + rel, null, null, "@", null, p));
            });
        } catch (IOException ignored) { }
        return out;
    }

    static void welcome(Buffer t) {
        int a = t.length();
        t.append("欢迎使用 AgentForge TUI 演示\n", "markdown-header-face-1");
        t.append("这是一个假的 agent 会话：输入框里打字，RET 发送；回复由后台线程流式写进来。\n"
                + "在 transcript 里（C-x o 过去）：n / p 跳块、TAB 开合折叠、RET 点按钮、C-s 搜索。\n", "default");
        Fold.markBlock(t, a, t.length(), "welcome");
        t.append("\n");
    }

    static void userBlock(Buffer t, String text) {
        int a = t.length();
        t.append("▍你\n", "block-title");
        t.append(text + "\n", "default");
        Fold.markBlock(t, a, t.length(), "user");
        t.append("\n");
    }

    /** 后台线程模拟流式回复：每 25ms 投一小段；中间有一个工具调用（输出折叠）和一个按钮。 */
    static void streamReply(Editor ed, Buffer t, String text) {
        String reply = "好的，我来看看「" + text + "」。先列一下项目里的文件，然后给出结论。中文、English 与 emoji 🚀 混排时按显示宽度折行。";
        Thread th = new Thread(() -> {
            int[] start = new int[1];
            ed.post(() -> {
                start[0] = t.length();
                t.append("▍助手\n", "block-title");
            });
            for (int i = 0; i < reply.length(); i += 3) {
                String chunk = reply.substring(i, Math.min(reply.length(), i + 3));
                ed.post(() -> t.append(chunk, "default"));
                sleep(25);
            }
            ed.post(() -> {
                t.append("\n");
                int h = t.length();
                t.append("⚙ 工具调用 list_files（TAB 展开）\n", "font-lock-function-name-face");
                int body = t.length();
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= 12; i++) sb.append("  src/main/java/demo/File").append(i).append(".java\n");
                t.append(sb.toString(), "shadow");
                Fold.create(t, body, t.length(), "  … 12 行输出已折叠", true);
                Fold.markBlock(t, h, t.length(), "tool");
                t.append("结论：一切正常。", "default");
                t.append(" ");
                Overlay btn = t.append("[再来一次]", "button");
                btn.action(e -> streamReply(e, t, text));
                t.append("\n");
                Fold.markBlock(t, start[0], t.length(), "assistant");
                t.append("\n");
            });
        }, "demo-agent");
        th.setDaemon(true);
        th.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
