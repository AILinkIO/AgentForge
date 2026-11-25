package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.commands.DefaultKeymaps;
import io.ailink.agentforge.tui.commands.DocumentCommands;
import io.ailink.agentforge.tui.commands.EditingCommands;
import io.ailink.agentforge.tui.commands.HelpCommands;
import io.ailink.agentforge.tui.commands.SearchCommands;
import io.ailink.agentforge.tui.commands.ThemeCommands;
import io.ailink.agentforge.tui.commands.WindowCommands;
import io.ailink.agentforge.tui.term.Colors;
import io.ailink.agentforge.tui.term.Face;
import io.ailink.agentforge.tui.term.Grid;
import io.ailink.agentforge.tui.term.InputEvent;
import io.ailink.agentforge.tui.term.Terminal;
import io.ailink.agentforge.tui.theme.Themes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * TUI 内核的中心：全部 UI 状态（buffer、窗口布局、minibuffer、键表、命令、主题 …）与命令循环。
 * 设计照 coxswain（designs/clj-editor.md §2）：<b>一条 UI 线程独占可变状态</b>；输入线程、定时器、后台任务只往队列里投。
 * coxswain 的状态是不可变 map、命令是纯函数；这里换成 Java 习惯的可变对象，命令直接改状态（{@link Command}）。
 *
 * <h2>线程</h2>
 * 除了 {@link #post(Runnable)}，所有方法都只能在 UI 线程上调（命令、钩子、定时器、post 进来的 Runnable 里都是 UI 线程）。
 * 后台线程（agent 会话的事件线程）要改 buffer：{@code ed.post(() -> transcript.append("…", "default"))}。
 * 一批事件处理完才画一帧（重绘合并），没有帧率。
 *
 * <h2>无头 / 测试</h2>
 * {@code new Editor(80, 24)} 不需要终端：{@link #feed(String)} 喂键（kbd 写法）、{@link #typeText}、{@link #render()} 出格子、
 * {@link #drainPosted()} 排空 post 队列、{@link #setClock} + {@link #runTimers()} 驱动定时器。真终端用 {@link TuiRunner}。
 */
public final class Editor {
    // ── 子系统 ────────────────────────────────────────────────────────
    final Options options = new Options();
    final Hooks hooks = new Hooks(this);
    final EventBus bus = new EventBus();
    final Timers timers = new Timers();
    final View view = new View(this);
    final Display display = new Display(this);
    final Mouse mouse = new Mouse(this);

    // ── 命令与键表 ────────────────────────────────────────────────────
    public record CommandInfo(String name, String doc, Command fn, boolean internal) {}

    private final Map<String, CommandInfo> commands = new LinkedHashMap<>();
    private final Keymap globalMap = new Keymap("global");
    private final Keymap minibufferMap = new Keymap("minibuffer");

    // ── buffer / 窗口 ─────────────────────────────────────────────────
    private final Map<String, Buffer> buffers = new LinkedHashMap<>();
    private final List<Buffer> bufferOrder = new ArrayList<>();
    LayoutNode root;
    Window selected;
    private int nextWindowId = 1;
    final Buffer miniBuffer;
    final Window miniWindow;
    private Window editWindowOverride;
    private ModeLine modeLine = ModeLine.standard();

    // ── 命令循环状态 ──────────────────────────────────────────────────
    /** 前缀参数：null、Integer（C-u 3 / M-3）、{@link #RAW_PREFIX} 包着的 int[]（C-u ⇒ {4}）、{@link #MINUS}。 */
    Object prefixArg, nextPrefixArg;
    boolean nextPrefixSet;
    public static final Object MINUS = "-";
    String thisCommand, lastCommand, thisKey;
    List<String> thisKeys = List.of();
    final List<String> pending = new ArrayList<>();
    long pendingSince;
    boolean whichKeyVisible;
    private Timers.Timer whichKeyTimer;
    boolean escPrefix;
    private final Deque<KeyInterceptor> interceptors = new ArrayDeque<>();
    String describeKeyMode;
    String message;
    private final List<String> killRing = new ArrayList<>();
    int yankIndex;
    List<String> macroRecording;
    List<String> lastMacro;
    private boolean playingMacro;
    String repeatCommand;
    Object pendingRun;   // M-x 选好的命令：{name, prefixArg}
    private Runnable afterCommandAction;
    private int amalgCount;

    // ── minibuffer / 补全 / 浮框 / isearch ─────────────────────────────
    MinibufferState mb;
    private final Map<String, List<String>> histories = new HashMap<>();
    final List<CompletionSource> completionSources = new ArrayList<>();
    CompletionState completion;
    private Timers.Timer completionTimer;
    final List<Popup> popups = new ArrayList<>();
    Isearch isearch;

    // ── 外观 ──────────────────────────────────────────────────────────
    private String theme;
    private Map<String, Face> faces = new HashMap<>();
    private final Map<String, Face> faceOverrides = new HashMap<>();
    private final Map<String, Face> resolved = new HashMap<>();
    private final Map<List<Object>, Face> stackCache = new HashMap<>();
    private int colorDepth = 24;

    // ── 运行时 ────────────────────────────────────────────────────────
    int cols, rows;
    Terminal terminal;
    boolean quit;
    volatile boolean dirty = true;
    private final ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();
    volatile Runnable wakeHook = () -> {};
    String pendingClipboard;
    private Consumer<Throwable> errorLogger = t -> {};

    static final class MinibufferState {
        MinibufferRequest req;
        int sel;
        String lastInput;
        int histIdx = -1;
        Window returnWindow;
    }

    static final class CompletionState {
        CompletionSource source;
        Buffer buffer;
        int start;
        List<CompletionItem> items = List.of();
        int sel;
        boolean auto;
    }

    public Editor(int cols, int rows) {
        this.cols = Math.max(10, cols);
        this.rows = Math.max(3, rows);
        miniBuffer = new Buffer(" *Minibuf*");
        miniWindow = new Window(0, miniBuffer);
        Buffer scratch = getOrCreateBuffer("*scratch*");
        Window w = new Window(nextWindowId++, scratch);
        root = w;
        selected = w;
        EditingCommands.install(this);
        WindowCommands.install(this);
        HelpCommands.install(this);
        SearchCommands.install(this);
        ThemeCommands.install(this);
        DocumentCommands.install(this);
        DefaultKeymaps.install(this);
        loadTheme(Themes.defaultTheme());
    }

    // ══ 命令注册 ══════════════════════════════════════════════════════

    /** 定义 / 替换命令（名字照 Emacs 的写法，doc 是一行中文说明）。M-x 里会列出来。 */
    public void defineCommand(String name, String doc, Command fn) { commands.put(name, new CommandInfo(name, doc, fn, false)); }

    /** 内部命令：能绑键，但 M-x 不列（照 coxswain：只在某种 buffer 自己的键表里有意义的命令别暴露给 M-x）。 */
    public void defineInternalCommand(String name, String doc, Command fn) { commands.put(name, new CommandInfo(name, doc, fn, true)); }

    public CommandInfo command(String name) { return commands.get(name); }

    /** M-x 的候选：非内部命令，按名字排序。 */
    public List<String> commandNames() {
        return commands.values().stream().filter(c -> !c.internal).map(CommandInfo::name).sorted().toList();
    }

    public Keymap globalMap() { return globalMap; }
    public Keymap minibufferMap() { return minibufferMap; }

    // ══ 子系统访问 ════════════════════════════════════════════════════

    public Options options() { return options; }
    public Hooks hooks() { return hooks; }
    public EventBus bus() { return bus; }
    public ModeLine modeLine() { return modeLine; }
    public void setModeLine(ModeLine m) { modeLine = m; }
    public int cols() { return cols; }
    public int rows() { return rows; }
    public void setErrorLogger(Consumer<Throwable> l) { errorLogger = l; }
    void logError(Throwable t) { try { errorLogger.accept(t); } catch (RuntimeException ignored) { } }

    // ══ buffer ═══════════════════════════════════════════════════════

    public Buffer buffer(String name) { return buffers.get(name); }

    /** 有就拿，没有就建（名字唯一）。 */
    public Buffer getOrCreateBuffer(String name) {
        Buffer b = buffers.get(name);
        if (b == null) {
            b = new Buffer(name);
            buffers.put(name, b);
            bufferOrder.add(b);
        }
        return b;
    }

    /** 新 buffer；同名已有就加 &lt;2&gt; &lt;3&gt;。 */
    public Buffer createBuffer(String base) {
        String n = base;
        for (int i = 2; buffers.containsKey(n); i++) n = base + "<" + i + ">";
        return getOrCreateBuffer(n);
    }

    /** 全部 buffer（不含 minibuffer），最近显示过的在前。 */
    public List<Buffer> buffers() { return List.copyOf(bufferOrder); }

    /** 关掉 buffer：显示它的窗口换成别的 buffer；至少留一个 *scratch*。 */
    public void killBuffer(Buffer b) {
        if (!buffers.containsKey(b.name)) return;
        hooks.run(Hooks.KILL_BUFFER, b);
        buffers.remove(b.name);
        bufferOrder.remove(b);
        if (bufferOrder.isEmpty()) getOrCreateBuffer("*scratch*");
        Buffer other = bufferOrder.getFirst();
        for (Window w : windows()) if (w.buffer == b) showBuffer(w, other);
        if (completion != null && completion.buffer == b) closeCompletion();
    }

    public void renameBuffer(Buffer b, String newName) {
        if (buffers.containsKey(newName)) throw new UserError("Buffer name `%s' is in use", newName);
        buffers.remove(b.name);
        b.name = newName;
        buffers.put(newName, b);
    }

    // ══ 当前窗口 / buffer / point ═════════════════════════════════════

    public boolean minibufferActive() { return mb != null; }

    /** 当前窗口：withCurrentBuffer 临时指定的优先，其次 minibuffer（开着时），再次选中的窗口。 */
    public Window currentWindow() {
        if (editWindowOverride != null) return editWindowOverride;
        return mb != null ? miniWindow : selected;
    }

    public Window selectedWindow() { return selected; }
    public Buffer currentBuffer() { return currentWindow().buffer; }
    public int point() { return currentWindow().point(); }

    /** point 夹到 [0, len]。 */
    public void goTo(int pos) { currentWindow().setPoint(Math.max(0, Math.min(pos, currentBuffer().length()))); }

    /**
     * 在 buffer b 上跑 r（借一个临时窗口，不切真窗口）：里面的 currentBuffer / point / 编辑原语都作用于 b（Emacs 的
     * with-current-buffer）。point 从 b 显示在的第一个窗口取，没有就是末尾。
     */
    public void withCurrentBuffer(Buffer b, Runnable r) {
        if (b == currentBuffer()) { r.run(); return; }
        Window prev = editWindowOverride;
        Window tmp = new Window(-1, b);
        Window shown = windows().stream().filter(w -> w.buffer == b).findFirst().orElse(null);
        tmp.setPoint(shown != null ? shown.point() : b.length());
        editWindowOverride = tmp;
        try {
            r.run();
        } finally {
            editWindowOverride = prev;
            tmp.point.detach();
            tmp.start.detach();
        }
    }

    // ══ 用户编辑（查只读）══════════════════════════════════════════════

    /** 当前 buffer 只读就抛 UserError。 */
    public void checkWritable() {
        Buffer b = currentBuffer();
        if (b.readOnly) throw new UserError("Buffer is read-only: %s", b.name);
    }

    /** 把 [a, b) 换成 s，point 放在插入的末尾。查只读（用户命令用这个；程序写文档用 Buffer 的方法）。 */
    public void replaceRegion(int a, int b, String s) {
        checkWritable();
        Buffer buf = currentBuffer();
        int lo = Math.max(0, Math.min(Math.min(a, b), buf.length()));
        int hi = Math.max(lo, Math.min(Math.max(a, b), buf.length()));
        buf.replace(lo, hi, s);
        goTo(lo + s.length());
    }

    public void insert(String s) { int p = point(); replaceRegion(p, p, s); }
    public void deleteRegion(int a, int b) { replaceRegion(a, b, ""); }

    /** 设 mark 并激活区域。 */
    public void setMark(int pos) { currentBuffer().setMark(Math.max(0, Math.min(pos, currentBuffer().length())), true); }

    public boolean regionActive() { return currentBuffer().markActive(); }

    /** 区域 {start, end}；没有 mark 抛 UserError。 */
    public int[] region() {
        Integer m = currentBuffer().mark;
        if (m == null) throw new UserError("The mark is not set now, so there is no region");
        int p = point();
        return new int[]{Math.min(m, p), Math.max(m, p)};
    }

    // ══ echo area ═════════════════════════════════════════════════════

    /** echo area 显示一条消息（过 i18n；下一条命令清掉）。 */
    public void message(String fmt, Object... args) { message = I18n.format(fmt, args); dirty = true; }

    /** 不过 i18n 的原文消息。 */
    public void messageRaw(String s) { message = s; dirty = true; }

    public String currentMessage() { return message; }

    // ══ kill ring ═════════════════════════════════════════════════════

    static final Set<String> KILL_COMMANDS = Set.of("kill-line", "kill-region", "kill-word", "backward-kill-word",
            "kill-whole-line", "kill-ring-save");

    /** 放进 kill ring；上一条命令也是 kill 时接在后面（prepend 时接在前面）。开了 OSC 52 就同步系统剪贴板。 */
    public void killNew(String s, boolean prepend) {
        if (lastCommand != null && KILL_COMMANDS.contains(lastCommand) && !killRing.isEmpty()) {
            String top = killRing.removeLast();
            killRing.add(prepend ? s + top : top + s);
        } else {
            killRing.add(s);
            while (killRing.size() > options.getInt("kill-ring-max")) killRing.removeFirst();
        }
        yankIndex = 0;
        if (options.getBool("clipboard-osc52")) pendingClipboard = killRing.getLast();
    }

    public void killNew(String s) { killNew(s, false); }
    public List<String> killRing() { return List.copyOf(killRing); }
    public String currentKill() { return killRing.isEmpty() ? null : killRing.getLast(); }
    String killAt(int i) { return killRing.get(Math.floorMod(killRing.size() - 1 - i, killRing.size())); }

    // ══ 前缀参数 ═════════════════════════════════════════════════════

    /** 原始前缀参数（见字段说明）。 */
    public Object prefixArg() { return prefixArg; }
    public boolean rawPrefix() { return prefixArg instanceof int[]; }

    /** 数字前缀参数，默认 1。 */
    public int countArg() {
        Object p = prefixArg;
        if (p == null) return 1;
        if (p instanceof Integer i) return i;
        if (p instanceof int[] a) return a[0];
        if (p == MINUS) return -1;
        return 1;
    }

    /** 命令里设下一条命令的前缀参数（universal-argument / digit-argument 用）。 */
    public void setNextPrefixArg(Object p) { nextPrefixArg = p; nextPrefixSet = true; }

    public String thisKey() { return thisKey; }
    public List<String> thisKeys() { return thisKeys; }
    public String lastCommand() { return lastCommand; }
    public String thisCommand() { return thisCommand; }

    // ══ 命令循环 ══════════════════════════════════════════════════════

    /** 当前生效的键表链（照 Emacs current-active-maps）：point 处覆盖层的表 → 次要模式表 → buffer 自己的表 → 主模式表 → minibuffer 表 → 全局表。 */
    public List<Keymap> activeKeymaps() {
        List<Keymap> out = new ArrayList<>();
        Buffer b = currentBuffer();
        int p = point();
        for (Overlay o : b.overlaysAt(p)) if (o.keymap != null) out.add(o.keymap);
        if (p > 0) for (Overlay o : b.overlaysAt(p - 1)) if (o.keymap != null && o.end() == p && !out.contains(o.keymap)) out.add(o.keymap);
        for (MinorMode m : b.minorModes) out.add(m.keymap());
        if (b.localKeymap != null) out.add(b.localKeymap);
        out.add(b.mode.keymap());
        if (mb != null) out.add(minibufferMap);
        out.add(globalMap);
        return out;
    }

    private static final Set<String> NO_MESSAGE_RESET = Set.of("universal-argument", "digit-argument", "negative-argument");
    private static final Set<String> AMALGAMATING = Set.of("self-insert-command", "delete-backward-char", "delete-char");

    /** 按名字跑命令（M-x、程序调用都走这里）。k = 触发它的最后一个键（self-insert 用），可为 null。 */
    public void runCommand(String name, String k) {
        CommandInfo ci = commands.get(name);
        if (ci == null) {
            pending.clear();
            message("Symbol's function definition is void: %s", name);
            return;
        }
        runCommand(name, ci.fn, k);
    }

    void runCommand(String name, Command fn, String k) {
        pending.clear();
        Buffer before = currentBuffer();
        long tickBefore = before.text.tick();
        LayoutNode rootBefore = root;
        Window selBefore = selected;
        int layoutSig = layoutSignature();
        thisCommand = name;
        thisKey = k;
        nextPrefixSet = false;
        if (!NO_MESSAGE_RESET.contains(name)) message = null;
        // undo 边界：连续打字每 20 个字合一组（Emacs 的 amalgamation）
        for (Buffer b : bufferOrder) if (b != before) b.undo.boundary();
        miniBuffer.undo.boundary();
        if (AMALGAMATING.contains(name) && name.equals(lastCommand) && amalgCount < 20 && before.undo.openSize() > 0) amalgCount++;
        else { before.undo.boundary(); amalgCount = 1; }
        before.undo.pointHint = point();
        int pointBefore = point();
        Window winBefore = currentWindow();
        try {
            fn.run(this);
        } catch (UserError e) {
            messageRaw(e.getMessage());
        } catch (RuntimeException e) {
            logError(e);
            messageRaw(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            before.undo.pointHint = -1;
        }
        if (nextPrefixSet) {
            prefixArg = nextPrefixArg;
            nextPrefixSet = false;
        } else {
            prefixArg = null;
            lastCommand = name;
            if (!"repeat".equals(name)) repeatCommand = name;
            // 改了 buffer 的命令跑完，区域熄掉（transient-mark-mode）
            if (before.text.tick() != tickBefore && !"yank".equals(name)) before.deactivateMark();
        }
        afterCommand(currentWindow() == winBefore ? pointBefore : -1);
        if (root != rootBefore || selected != selBefore || layoutSig != layoutSignature()) hooks.run(Hooks.WINDOW_CHANGE);
    }

    private int layoutSignature() { return windows().hashCode() * 31 + System.identityHashCode(root); }

    private void afterCommand(int pointBefore) {
        syncMinibufferSelection();
        adjustPointOutOfInvisible(pointBefore);
        if (completion != null) refreshCompletion();
        if (afterCommandAction != null) {
            Runnable r = afterCommandAction;
            afterCommandAction = null;
            r.run();
        }
        if (pendingRun instanceof Object[] pr) {
            pendingRun = null;
            prefixArg = pr[1];
            runCommand((String) pr[0], thisKey);
        }
    }

    /** point 不停在隐藏文字里面（折叠块）：挪到隐藏段的头或尾。 */
    private void adjustPointOutOfInvisible(int before) {
        Window w = currentWindow();
        int p = w.point();
        int[] iv = w.buffer.invisibleAt(p);
        if (iv == null || p <= iv[0]) return;
        boolean forward = before >= 0 ? p > before : iv[1] - p < p - iv[0];
        w.setPoint(forward ? iv[1] : iv[0]);
    }

    /** Emacs 的 shift-translation：C-S-a ⇒ C-a、S-&lt;up&gt; ⇒ &lt;up&gt;；没有 Shift ⇒ null。 */
    private static String unshift(String k) { return k.contains("S-") ? k.replaceFirst("S-", "") : null; }

    /** 处理一个键（Emacs 键名）。 */
    public void handleKey(String k) {
        dirty = true;
        if (macroRecording != null && !playingMacro) macroRecording.add(k);
        hideWhichKey();
        if (escPrefix) { escPrefix = false; k = io.ailink.agentforge.tui.term.InputDecoder.addMeta(k); }
        else if (k.equals("ESC") && pending.isEmpty() && interceptors.isEmpty() && options.getBool("esc-prefix")) {
            // 照 Emacs：ESC 就是 Meta 前缀，之后不管隔多久，下一个键都当 M-键（选项 esc-prefix 关掉时 ESC 当普通键派发）
            escPrefix = true;
            return;
        }
        if (!interceptors.isEmpty()) {
            KeyInterceptor ki = interceptors.peek();
            boolean ate;
            try {
                ate = ki.handleKey(this, k);
            } catch (UserError e) {
                messageRaw(e.getMessage());
                ate = true;
            } catch (RuntimeException e) {
                logError(e);
                interceptors.remove(ki);
                messageRaw(e.getClass().getSimpleName() + ": " + e.getMessage());
                ate = true;
            }
            if (ate) { afterInterceptedKey(); return; }
            // 没吃掉：拦截器该自己撤掉（补全框除外 —— 它留着，命令跑完重新过滤）
            if (!(ki instanceof CompletionInterceptor)) interceptors.remove(ki);
        }
        dispatch(k);
    }

    private void afterInterceptedKey() {
        adjustPointOutOfInvisible(-1);
        if (completion != null) refreshCompletion();
    }

    private void dispatch(String k) {
        // 前缀参数输到一半：数字键继续算数（M-3 M-0 = 30、C-u 1 2）
        if (prefixArg != null && pending.isEmpty() && k.matches("(?:C-|M-|C-M-)?[0-9]|-")
                && (!k.equals("-") || prefixArg instanceof int[])) {
            thisKeys = List.of(k);
            runCommand(k.equals("-") ? "negative-argument" : "digit-argument", k);
            return;
        }
        List<String> keys = new ArrayList<>(pending);
        keys.add(k);
        List<Keymap> maps = activeKeymaps();
        Object b = Keymap.lookup(maps, keys);
        String k2;
        if (b == null && (k2 = unshift(k)) != null) {
            List<String> keys2 = new ArrayList<>(pending);
            keys2.add(k2);
            Object b2 = Keymap.lookup(maps, keys2);
            if (b2 != null) { k = k2; keys = keys2; b = b2; }
        }
        if (k.equals("C-g") && !pending.isEmpty()) {
            pending.clear();
            prefixArg = null;
            describeKeyMode = null;
            message("Quit");
            return;
        }
        if (b instanceof Keymap) {
            pending.clear();
            pending.addAll(keys);
            pendingSince = timers.now();
            scheduleWhichKey();
            return;
        }
        if (describeKeyMode != null) {
            pending.clear();
            Object cmd = b != null ? b : (keys.size() == 1 && Keymap.printable(k) ? "self-insert-command" : null);
            HelpCommands.describeKeyDone(this, keys, cmd);
            return;
        }
        thisKeys = keys;
        if (b instanceof String name) runCommand(name, k);
        else if (b instanceof Command c) runCommand("<anonymous>", c, k);
        else if (pending.isEmpty() && Keymap.printable(k)) runCommand("self-insert-command", k);
        else {
            pending.clear();
            prefixArg = null;
            message("%s is undefined", Keymap.keyDesc(keys));
        }
    }

    private void scheduleWhichKey() {
        if (whichKeyTimer != null) whichKeyTimer.cancel();
        whichKeyTimer = runAfter(options.getInt("which-key-delay-ms"), () -> {
            if (!pending.isEmpty()) { whichKeyVisible = true; dirty = true; }
        });
    }

    private void hideWhichKey() {
        whichKeyVisible = false;
        if (whichKeyTimer != null) { whichKeyTimer.cancel(); whichKeyTimer = null; }
    }

    /** which-key 弹着吗（前缀键按了一半、停了 which-key-delay-ms）。 */
    public boolean whichKeyVisible() { return whichKeyVisible && !pending.isEmpty(); }
    public List<String> pendingKeys() { return List.copyOf(pending); }

    /** 压一个模态收键器（见 {@link KeyInterceptor}）。 */
    public void pushKeyInterceptor(KeyInterceptor k) { interceptors.push(k); dirty = true; }
    public void popKeyInterceptor(KeyInterceptor k) { interceptors.remove(k); dirty = true; }

    /** 读下一个键交给 f（一次性）。prompt 显示在 echo area。 */
    public void readKey(String prompt, Consumer<String> f) {
        if (prompt != null) message(prompt);
        KeyInterceptor[] self = new KeyInterceptor[1];
        self[0] = (ed, k) -> {
            popKeyInterceptor(self[0]);
            message = null;
            f.accept(k);
            return true;
        };
        pushKeyInterceptor(self[0]);
    }

    /** y-or-n-p：按 y / n（C-g 当 n）。 */
    public void yOrN(String prompt, Consumer<Boolean> f) {
        String p = I18n.t(prompt) + I18n.t(" (y or n) ");
        KeyInterceptor[] self = new KeyInterceptor[1];
        self[0] = (ed, k) -> {
            switch (k) {
                case "y", "Y", "SPC" -> { popKeyInterceptor(self[0]); message = null; f.accept(true); }
                case "n", "N", "DEL", "C-g" -> { popKeyInterceptor(self[0]); message = null; f.accept(false); }
                default -> messageRaw(p);
            }
            return true;
        };
        messageRaw(p);
        pushKeyInterceptor(self[0]);
    }

    /** 处理一个输入事件（键、粘贴、鼠标、尺寸）；之后跑 post-command 钩子。 */
    public void handleEvent(InputEvent ev) {
        dirty = true;
        try {
            switch (ev) {
                case InputEvent.Key key -> handleKey(key.key());
                case InputEvent.Paste p -> paste(p.text());
                case InputEvent.Mouse m -> mouse.handle(m);
                case InputEvent.Resize r -> resize(r.cols(), r.rows());
                default -> { }
            }
        } catch (UserError e) {
            messageRaw(e.getMessage());
        } catch (RuntimeException e) {
            logError(e);
            messageRaw(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        hooks.run(Hooks.POST_COMMAND);
    }

    /** bracketed paste：整段插入，单独一组 undo（不触发补全）。 */
    void paste(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (isearch != null) { isearch.addString(this, t); return; }
        runCommand("yank-paste", (Command) ed -> ed.insert(t), null);
    }

    public void resize(int c, int r) {
        cols = Math.max(10, c);
        rows = Math.max(3, r);
        dirty = true;
        hooks.run(Hooks.RESIZE);
    }

    /** 测试 / 程序：按 kbd 写法喂一串键（"C-x o M-x"）。 */
    public Editor feed(String keys) {
        for (String k : Keymap.kbd(keys)) handleKey(k);
        return this;
    }

    /** 测试 / 程序：逐字打字（每个字一个键；换行 = RET）。 */
    public Editor typeText(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            handleKey(cp == '\n' ? "RET" : cp == ' ' ? "SPC" : new String(Character.toChars(cp)));
        }
        return this;
    }

    public void quit() { quit = true; }
    public boolean quitRequested() { return quit; }

    // ══ 键盘宏 ═══════════════════════════════════════════════════════

    public boolean recordingMacro() { return macroRecording != null; }

    void playMacro(List<String> keys, int times) {
        playingMacro = true;
        try {
            for (int i = 0; i < times; i++) for (String k : keys) handleKey(k);
        } finally {
            playingMacro = false;
        }
    }

    // ══ 线程 / 定时器 ═════════════════════════════════════════════════

    /** 从任意线程投一个 Runnable 给 UI 线程（之后重画）。线程安全。 */
    public void post(Runnable r) {
        posted.add(r);
        wakeHook.run();
    }

    /** 跑完 post 队列里已有的（预算 budgetMs 毫秒；测试里 drainPosted 不限）。⇒ 跑了几个。 */
    int drainPosted(long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        int n = 0;
        for (Runnable r; (r = posted.poll()) != null; ) {
            n++;
            dirty = true;
            try {
                r.run();
            } catch (UserError e) {
                messageRaw(e.getMessage());
            } catch (RuntimeException e) {
                reportError(e);
            }
            for (Buffer b : bufferOrder) b.undo.boundary();
            if (System.currentTimeMillis() > deadline) break;
        }
        return n;
    }

    /** 测试：把 post 队列排空。 */
    public int drainPosted() { return drainPosted(Long.MAX_VALUE / 4); }

    boolean hasPosted() { return !posted.isEmpty(); }

    void reportError(Throwable e) {
        logError(e);
        messageRaw(I18n.format("error: %s", e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    /** delayMs 毫秒后在 UI 线程上跑一次。 */
    public Timers.Timer runAfter(long delayMs, Runnable r) { return timers.add(delayMs, 0, r); }

    /** 每 periodMs 毫秒跑一次（第一次在 delayMs 后）。 */
    public Timers.Timer runEvery(long delayMs, long periodMs, Runnable r) { return timers.add(delayMs, Math.max(1, periodMs), r); }

    /** 测试：换时钟。 */
    public void setClock(LongSupplier clock) { timers.clock = clock; }

    /** 编辑器的钟（毫秒；测试里可以换成假钟）：转圈之类的动画按它走。 */
    public long now() { return timers.clock.getAsLong(); }

    /** 跑到期的定时器。⇒ 跑了几个。 */
    public int runTimers() {
        int n = timers.runDue(this);
        if (n > 0) dirty = true;
        return n;
    }

    // ══ 窗口 ═════════════════════════════════════════════════════════

    public LayoutNode layoutRoot() { return root; }

    /** 全部窗口，布局顺序。 */
    public List<Window> windows() { return root.windows(); }

    /** 选中窗口 w（minibuffer 开着时也换：minibuffer 结束后回到这里）。 */
    public void selectWindow(Window w) {
        if (!windows().contains(w)) return;
        selected = w;
        bufferOrder.remove(w.buffer);
        bufferOrder.addFirst(w.buffer);
        dirty = true;
    }

    /** 在窗口 w 里显示 b（离开的 buffer 记下位置，换回来时恢复）。 */
    public void showBuffer(Window w, Buffer b) {
        if (w.buffer == b) return;
        w.prevPositions.put(w.buffer, new int[]{w.point(), w.start()});
        int[] pos = w.prevPositions.remove(b);
        w.attach(b, pos != null ? pos[0] : (w.followTail ? b.length() : 0), pos != null ? pos[1] : 0);
        w.setFollowTail(w.followTail);
        bufferOrder.remove(b);
        bufferOrder.addFirst(b);
        dirty = true;
    }

    /** 在当前选中的窗口里显示 b。 */
    public void switchToBuffer(Buffer b) {
        if (selected.flag(Window.DEDICATED)) throw new UserError("Window is dedicated to `%s'", selected.buffer.name);
        showBuffer(selected, b);
    }

    /** 把窗口 w（或它所在的原子组）分成两半，新窗口显示 buffer（null = 同一个 buffer）。⇒ 新窗口。 */
    public Window splitWindow(Window w, Split.Dir dir, double ratio, Buffer buffer) {
        Split grp = w.atomicRoot();
        LayoutNode target = grp != null ? grp : w;
        Window nw = new Window(nextWindowId++, buffer != null ? buffer : w.buffer);
        if (buffer == null) { nw.setPoint(w.point()); nw.setStart(w.start()); }
        Split parent = target.parent;
        Split s = new Split(dir, ratio, target, nw);
        if (parent == null) root = s; else parent.replaceChild(target, s);
        s.parent = parent;
        dirty = true;
        return nw;
    }

    /** 把两个相邻（同一个父节点）的窗口标成原子组 name。⇒ 组节点。 */
    public Split makeAtomic(Window a, Window b, String name) {
        if (a.parent == null || a.parent != b.parent) throw new IllegalArgumentException("windows are not siblings");
        return a.parent.setAtomic(name);
    }

    /** 删窗口 w（在原子组里 ⇒ 删整个组）；只剩一个时报错。 */
    public void deleteWindow(Window w) {
        Split grp = w.atomicRoot();
        LayoutNode target = grp != null ? grp : w;
        if (target == root) throw new UserError("Attempt to delete minibuffer or sole ordinary window");
        List<Window> order = windows();
        List<Window> gone = target.windows();
        Split parent = target.parent;
        LayoutNode sibling = parent.a == target ? parent.b : parent.a;
        Split gp = parent.parent;
        if (gp == null) { root = sibling; sibling.parent = null; } else gp.replaceChild(parent, sibling);
        for (Window g : gone) { g.point.detach(); g.start.detach(); }
        if (gone.contains(selected)) {
            int idx = order.indexOf(selected);
            List<Window> rest = windows();
            Window pick = null;
            for (int i = idx; i < order.size() + idx && pick == null; i++) {
                Window c = order.get(i % order.size());
                if (rest.contains(c) && !c.flag(Window.NO_OTHER_WINDOW)) pick = c;
            }
            selected = pick != null ? pick : rest.getFirst();
        }
        dirty = true;
    }

    /** 只留窗口 w（在原子组里 ⇒ 留整个组）。 */
    public void deleteOtherWindows(Window w) {
        Split grp = w.atomicRoot();
        LayoutNode keep = grp != null ? grp : w;
        for (Window g : windows()) if (!keep.windows().contains(g)) { g.point.detach(); g.start.detach(); }
        root = keep;
        keep.parent = null;
        if (!keep.windows().contains(selected)) selected = w;
        dirty = true;
    }

    /** 按布局顺序往后数 n 个窗口（可为负），跳过 no-other-window 的。 */
    public Window nextWindow(Window from, int n) {
        List<Window> order = windows();
        List<Window> cand = order.stream().filter(x -> x == from || !x.flag(Window.NO_OTHER_WINDOW)).toList();
        if (cand.isEmpty()) return from;
        int idx = Math.max(0, cand.indexOf(from));
        return cand.get(Math.floorMod(idx + n, cand.size()));
    }

    /** 在别的窗口显示 b（只有一个窗口就往下分一个），记下怎么回去（quit-window 用）。⇒ 那个窗口。 */
    public Window displayBufferOtherWindow(Buffer b) {
        for (Window w : windows()) if (w.buffer == b) return w;
        Window target;
        List<Window> others = windows().stream().filter(w -> w != selected && !w.flag(Window.DEDICATED)).toList();
        if (others.isEmpty()) {
            target = splitWindow(selected, Split.Dir.BELOW, 0.5, b);
            target.setParam("quit-restore", "delete");
        } else {
            target = others.getFirst();
            target.setParam("quit-restore", target.buffer);
            showBuffer(target, b);
        }
        return target;
    }

    /** 窗口在屏幕上的位置（含 mode line / header line 那几行）。 */
    public Map<Window, Rect> windowRects() {
        Map<Window, Rect> out = new LinkedHashMap<>();
        rects(root, 0, 0, cols, Math.max(1, rows - (echoOverlay() ? 0 : 1)), false, out);
        return out;
    }

    private static void rects(LayoutNode n, int x, int y, int w, int h, boolean divider, Map<Window, Rect> out) {
        if (n instanceof Window win) { out.put(win, new Rect(x, y, w, h, divider)); return; }
        Split s = (Split) n;
        if (s.dir == Split.Dir.BELOW) {
            int ha = s.firstSize(h);
            rects(s.a, x, y, w, ha, divider, out);
            rects(s.b, x, y + ha, w, h - ha, divider, out);
        } else {
            int wa = s.firstSize(w);
            rects(s.a, x, y, wa, h, true, out);
            rects(s.b, x + wa, y, w - wa, h, divider, out);
        }
    }

    boolean hasModeLine(Window w) { return w.buffer.modeLine != ModeLine.NONE; }
    boolean hasHeaderLine(Window w) { return w.buffer.headerLine != null; }
    /** 正文下面固定行的行数。 */
    public int footerRows(Window w) { return w.buffer.footer == null ? 0 : w.buffer.footer.apply(w).size(); }
    /** 窗口顶上那几行（header line 之前）的行数。 */
    public int topRows(Window w) { return w.buffer.topRows == null ? 0 : w.buffer.topRows.apply(w).size(); }

    /** echo area 叠在最后一行上（选项 echo-area = "overlay"）：没东西要说时不占行，窗口铺满整屏。 */
    boolean echoOverlay() { String v = options.getString("echo-area"); return "overlay".equals(v) || "host".equals(v); }

    /** echo-area = "host"：普通消息不由显示层画（宿主自己摆，例如 agent 工作台底栏的闪现行）；读键提问 / minibuffer 照常盖最后一行。 */
    boolean echoByHost() { return "host".equals(options.getString("echo-area")); }

    /** 有没有一个等着按键的模态提问（{@link #readKey} / {@link #yOrN}；补全弹框不算）：这时 echo area 里的话是问句，不是闪现。 */
    public boolean awaitingKey() {
        for (KeyInterceptor k : interceptors) if (!(k instanceof CompletionInterceptor)) return true;
        return false;
    }

    int gutterWidth(Window w) {
        if (w == miniWindow || !w.buffer.flag("display-line-numbers")) return 0;
        return String.valueOf(w.buffer.lineCount()).length() + 1;
    }

    /** 窗口正文区 {宽, 高}（扣掉 mode line、header line、竖分隔线、行号栏）。 */
    public int[] textSize(Window w) {
        if (w == miniWindow || w.id < 0) {
            if (w.id < 0) {
                Window shown = windows().stream().filter(x -> x.buffer == w.buffer).findFirst().orElse(null);
                if (shown != null) return textSize(shown);
            }
            return new int[]{cols, 1};
        }
        Rect r = windowRects().get(w);
        if (r == null) return new int[]{cols, Math.max(1, rows - 2)};
        int tw = r.w() - (r.divider() ? 1 : 0) - gutterWidth(w);
        int th = r.h() - (hasModeLine(w) ? 1 : 0) - (hasHeaderLine(w) ? 1 : 0) - footerRows(w) - topRows(w);
        return new int[]{Math.max(1, tw), Math.max(1, th)};
    }

    /** 窗口 w 的 start 挪 n 条视觉行（n &gt; 0 往下），point 跟着进窗口。 */
    public void scrollWindow(Window w, int n) { view.scroll(w, n); }

    /** 窗口能显示几行正文。 */
    public int windowHeight(Window w) { return textSize(w)[1]; }

    /** mode line 的位置标签：All / Top / Bot / NN%。 */
    String positionLabel(Window w) {
        int len = w.buffer.length();
        int start = w.start();
        boolean eob = display.eobVisible(w);
        if (start == 0 && eob) return "All";
        if (start == 0) return "Top";
        if (eob) return "Bot";
        return (100L * start / Math.max(1, len)) + "%";
    }

    // ══ minibuffer ═══════════════════════════════════════════════════

    /** 开 minibuffer（见 {@link MinibufferRequest}）。已经开着时报错（不支持递归 minibuffer）。 */
    public void readFromMinibuffer(MinibufferRequest r) {
        if (mb != null) throw new UserError("Command attempted to use minibuffer while in minibuffer");
        closeCompletion();
        mb = new MinibufferState();
        mb.req = r;
        miniBuffer.undo.clear();
        setMiniInput(r.initial);
        mb.lastInput = r.initial;
        hooks.run(Hooks.MINIBUFFER_SETUP);
        dirty = true;
    }

    /** completing-read：从 items 里选一个。 */
    public void completingRead(String prompt, List<String> items, Consumer<String> onDone) {
        readFromMinibuffer(new MinibufferRequest(prompt).candidates(items).requireMatch(true).onDone(onDone));
    }

    /** 读一行文字。 */
    public void readString(String prompt, String initial, Consumer<String> onDone) {
        readFromMinibuffer(new MinibufferRequest(prompt).initial(initial).onDone(onDone));
    }

    /** yes-or-no-p：要打全 yes / no。 */
    public void yesOrNo(String prompt, Runnable onYes) {
        readFromMinibuffer(new MinibufferRequest(I18n.t(prompt) + I18n.t(" (yes or no) ")).onDone(ans -> {
            switch (ans.trim().toLowerCase()) {
                case "yes" -> onYes.run();
                case "no" -> { }
                default -> message("Please answer yes or no.");
            }
        }));
    }

    public String minibufferInput() { return miniBuffer.contents(); }

    void setMiniInput(String s) {
        miniBuffer.setContents(s);
        miniWindow.setPoint(s.length());
    }

    /** 当前候选（过滤排序后）；没有候选表 ⇒ null。 */
    public List<String> minibufferCandidates() {
        if (mb == null || mb.req.completer == null) return null;
        String in = minibufferInput();
        return Matcher.match(in, mb.req.completer.apply(in));
    }

    public int minibufferSelection() { return mb == null ? -1 : mb.sel; }

    private void syncMinibufferSelection() {
        if (mb == null) return;
        String in = minibufferInput();
        if (!in.equals(mb.lastInput)) { mb.lastInput = in; mb.sel = 0; }
    }

    /** 提交 minibuffer（RET / M-j 的底层）。 */
    public void minibufferFinish(String text) {
        MinibufferState s = mb;
        if (s == null) return;
        mb = null;
        setMiniInput("");
        if (s.req.history != null && !text.isBlank()) {
            List<String> h = histories.computeIfAbsent(s.req.history, k -> new ArrayList<>());
            h.remove(text);
            h.addFirst(text);
            while (h.size() > 100) h.removeLast();
        }
        hooks.run(Hooks.MINIBUFFER_EXIT);
        s.req.onDone.accept(text);
    }

    /** C-g 退出 minibuffer。 */
    public void minibufferAbort() {
        MinibufferState s = mb;
        if (s == null) return;
        mb = null;
        setMiniInput("");
        hooks.run(Hooks.MINIBUFFER_EXIT);
        message("Quit");
        if (s.req.onAbort != null) s.req.onAbort.run();
    }

    String minibufferSelected() {
        List<String> c = minibufferCandidates();
        if (c == null || c.isEmpty()) return null;
        return c.get(Math.min(mb.sel, c.size() - 1));
    }

    void minibufferMoveSelection(int d) {
        List<String> c = minibufferCandidates();
        if (c == null || c.isEmpty()) return;
        mb.sel = Math.floorMod(mb.sel + d, c.size());
    }

    void minibufferHistory(int d) {
        List<String> h = histories.getOrDefault(mb.req.history, List.of());
        int i = Math.max(-1, Math.min(h.size() - 1, mb.histIdx + d));
        mb.histIdx = i;
        setMiniInput(i < 0 ? "" : h.get(i));
        mb.lastInput = null;
    }

    /** 历史表（输入框自己的 M-p / M-n 也可以用它存）。 */
    public List<String> history(String name) { return histories.computeIfAbsent(name, k -> new ArrayList<>()); }

    MinibufferRequest minibufferRequest() { return mb == null ? null : mb.req; }

    // ══ 浮框 ═════════════════════════════════════════════════════════

    public void showPopup(Popup p) { if (!popups.contains(p)) popups.add(p); dirty = true; }

    public void closePopup(Popup p) {
        if (popups.remove(p) && p.onClose != null) p.onClose.run();
        dirty = true;
    }

    public List<Popup> popups() { return List.copyOf(popups); }

    /** 菜单浮框：C-n/C-p/↑↓ 选、RET 确认（调 onSelect(下标)）、C-g / q 关。⇒ 浮框。 */
    public Popup popupMenu(String title, List<String> items, Consumer<Integer> onSelect) {
        Popup p = Popup.ofLines(items).title(title).select(0).onSelect(onSelect);
        showPopup(p);
        KeyInterceptor[] self = new KeyInterceptor[1];
        self[0] = (ed, k) -> {
            switch (k) {
                case "C-n", "<down>", "TAB" -> p.select(p.selected + 1);
                case "C-p", "<up>", "<backtab>" -> p.select(p.selected - 1);
                case "RET" -> {
                    popKeyInterceptor(self[0]);
                    closePopup(p);
                    if (p.onSelect != null && p.selected >= 0) p.onSelect.accept(p.selected);
                }
                case "C-g", "q", "ESC" -> { popKeyInterceptor(self[0]); closePopup(p); }
                default -> { }
            }
            return true;
        };
        pushKeyInterceptor(self[0]);
        return p;
    }

    // ══ 补全（corfu）══════════════════════════════════════════════════

    public void addCompletionSource(CompletionSource s) {
        completionSources.add(s);
        completionSources.sort(java.util.Comparator.comparingInt(CompletionSource::order));
    }

    public void removeCompletionSource(CompletionSource s) { completionSources.remove(s); }

    /** 补全框开着吗。 */
    public boolean completionActive() { return completion != null; }

    /** 当前（过滤后的）补全候选。 */
    public List<CompletionItem> completionCandidates() {
        if (completion == null) return List.of();
        String prefix = currentBuffer().substring(completion.start, Math.max(completion.start, point()));
        return filterItems(completion.items, prefix);
    }

    public int completionSelection() { return completion == null ? -1 : completion.sel; }

    static List<CompletionItem> filterItems(List<CompletionItem> items, String prefix) {
        if (prefix.isBlank()) return items;
        Map<String, List<CompletionItem>> by = new LinkedHashMap<>();
        for (CompletionItem it : items) by.computeIfAbsent(it.label(), k -> new ArrayList<>()).add(it);
        List<String> order = Matcher.match(prefix.toLowerCase(), new ArrayList<>(by.keySet()));
        List<CompletionItem> out = new ArrayList<>();
        // 和已经打出来的字一模一样的排最前
        for (String l : order) if (l.equals(prefix)) out.addAll(by.get(l));
        for (String l : order) if (!l.equals(prefix)) out.addAll(by.get(l));
        return out;
    }

    /** 开补全（completion-at-point 命令的底层）。auto = 自动弹出的（RET 不吞键）。 */
    public void startCompletion(boolean auto) {
        Buffer b = currentBuffer();
        List<CompletionSource> primary = new ArrayList<>(), fallback = new ArrayList<>();
        for (CompletionSource s : completionSources) {
            boolean ok;
            try { ok = s.applies(this) && s.start(this) >= 0; } catch (RuntimeException e) { ok = false; }
            if (ok) (s.fallback() ? fallback : primary).add(s);
        }
        List<CompletionSource> all = new ArrayList<>(primary);
        all.addAll(fallback);
        if (all.isEmpty()) { if (!auto) message("No completions"); return; }
        tryCompletionSource(all, 0, b, point(), auto);
    }

    private void tryCompletionSource(List<CompletionSource> srcs, int i, Buffer b, int pt, boolean auto) {
        if (i >= srcs.size()) { if (!auto) message("No completions"); return; }
        CompletionSource s = srcs.get(i);
        int start = s.start(this);
        String prefix = b.substring(start, pt);
        CompletableFuture<List<CompletionItem>> f;
        try { f = s.candidates(this, prefix); } catch (RuntimeException e) { tryCompletionSource(srcs, i + 1, b, pt, auto); return; }
        Consumer<List<CompletionItem>> onItems = items -> {
            if (currentBuffer() != b || point() != pt) return;           // 过时了
            if (items == null || filterItems(items, prefix).isEmpty()) { tryCompletionSource(srcs, i + 1, b, pt, auto); return; }
            CompletionState st = new CompletionState();
            st.source = s;
            st.buffer = b;
            st.start = start;
            st.items = items;
            st.auto = auto;
            boolean wasOpen = completion != null;
            completion = st;
            if (!wasOpen) pushKeyInterceptor(new CompletionInterceptor());
            dirty = true;
        };
        if (f.isDone()) onItems.accept(f.getNow(List.of()));
        else f.whenComplete((items, err) -> post(() -> onItems.accept(err != null ? null : items)));
    }

    public void closeCompletion() {
        if (completion == null) return;
        completion = null;
        interceptors.removeIf(k -> k instanceof CompletionInterceptor);
        dirty = true;
    }

    void acceptCompletion() {
        CompletionState st = completion;
        List<CompletionItem> c = completionCandidates();
        closeCompletion();
        if (st == null || c.isEmpty()) return;
        CompletionItem it = c.get(Math.min(st.sel, c.size() - 1));
        st.source.accept(this, it, st.start);
    }

    private void refreshCompletion() {
        CompletionState st = completion;
        if (st.buffer != currentBuffer() || point() < st.start) { closeCompletion(); return; }
        String typed = currentBuffer().substring(st.start, point());
        if (typed.isEmpty() && st.auto || typed.chars().anyMatch(Character::isWhitespace) || completionCandidates().isEmpty()) {
            closeCompletion();
            return;
        }
        st.sel = Math.min(st.sel, Math.max(0, completionCandidates().size() - 1));
    }

    /** self-insert 之后：打了触发字符 ⇒ 定时自动弹（completion-auto）。 */
    void maybeAutoComplete(String ch) {
        if (!options.getBool("completion-auto") || completion != null || mb != null) return;
        boolean trigger = false;
        // 打的就是触发字符，或者还在一个以触发字符开头的词里（"/th" 连着打完、定时器还没到）
        for (CompletionSource s : completionSources) {
            try { if (!s.triggerChars().isEmpty() && s.applies(this) && s.start(this) >= 0) trigger = true; }
            catch (RuntimeException ignored) { }
        }
        if (!trigger) return;
        if (completionTimer != null) completionTimer.cancel();
        Buffer b = currentBuffer();
        completionTimer = runAfter(options.getInt("completion-auto-delay-ms"), () -> {
            if (currentBuffer() == b && completion == null && mb == null) startCompletion(true);
        });
    }

    /** 补全框开着时先收键：上下选、TAB / RET 接受、C-g 关；别的键照常（之后重新过滤）。 */
    final class CompletionInterceptor implements KeyInterceptor {
        @Override public boolean handleKey(Editor ed, String k) {
            CompletionState st = completion;
            if (st == null) { interceptors.remove(this); return false; }
            List<CompletionItem> c = completionCandidates();
            switch (k) {
                case "C-n", "<down>", "M-n" -> { st.sel = c.isEmpty() ? 0 : Math.floorMod(st.sel + 1, c.size()); return true; }
                case "C-p", "<up>", "M-p" -> { st.sel = c.isEmpty() ? 0 : Math.floorMod(st.sel - 1, c.size()); return true; }
                case "TAB" -> { acceptCompletion(); return true; }
                case "RET" -> {
                    // 自动弹出的框里选中的就是已打全的字：RET 不吞键（照常发送 / 换行）
                    String typed = currentBuffer().substring(st.start, point());
                    if (st.auto && !c.isEmpty() && c.get(Math.min(st.sel, c.size() - 1)).insertText().equals(typed)) {
                        closeCompletion();
                        return false;
                    }
                    acceptCompletion();
                    return true;
                }
                case "C-g" -> { closeCompletion(); return true; }
                default -> { return false; }
            }
        }
    }

    // ══ 主题 / face ═══════════════════════════════════════════════════

    public String theme() { return theme; }

    /** 换主题（名字不认识抛 UserError）；之后整屏按新 face 重画。 */
    public void loadTheme(String name) {
        if (Themes.find(name) == null) throw new UserError("Unknown theme: %s", name);
        Object d = options.get("color-depth");
        colorDepth = d instanceof Integer i ? i : terminal != null ? terminal.colorDepth() : Colors.detectDepth();
        if (terminal == null && !(d instanceof Integer)) colorDepth = 24;
        faces = new HashMap<>(Themes.faces(name, colorDepth >= 24));
        theme = name;
        resolved.clear();
        stackCache.clear();
        dirty = true;
        hooks.run(Hooks.THEME_CHANGE, name);
    }

    /** 用户覆盖一个 face（换主题后照样压在上面；spec 为 null = 取消覆盖）。 */
    public void setFace(String name, Face spec) {
        if (spec == null) faceOverrides.remove(name); else faceOverrides.put(name, spec);
        resolved.clear();
        stackCache.clear();
        dirty = true;
    }

    /** face 的原始样式（主题 + 覆盖，未和 default 合成）；没有 ⇒ EMPTY。 */
    public Face faceSpec(String name) {
        Face o = faceOverrides.get(name);
        Face f = faces.getOrDefault(name, Face.EMPTY);
        return o != null ? f.merge(o) : f;
    }

    /** face 名 ⇒ 和 default 合成好的样式（缓存）。 */
    public Face resolveFace(String name) {
        Face r = resolved.get(name);
        if (r == null) {
            r = name.equals("default") ? faceSpec("default") : faceSpec("default").merge(faceSpec(name));
            resolved.put(name, r);
        }
        return r;
    }

    /** 一叠 face（低 ⇒ 高，元素是名字或 {@link Face}）合成好的样式（缓存）。 */
    Face resolveStack(List<Object> stack) {
        if (stack.isEmpty()) return resolveFace("default");
        if (stack.size() == 1 && stack.getFirst() instanceof String s) return resolveFace(s);
        Face r = stackCache.get(stack);
        if (r == null) {
            r = faceSpec("default");
            for (Object o : stack) r = r.merge(o instanceof String s ? faceSpec(s) : (Face) o);
            if (stackCache.size() > 4096) stackCache.clear();
            stackCache.put(List.copyOf(stack), r);
        }
        return r;
    }

    public int colorDepth() { return colorDepth; }

    // ══ 显示 ═════════════════════════════════════════════════════════

    /** 画一帧：⇒ 格子（光标位置见 {@link #cursor()}）。 */
    public Grid render() { return display.render(); }

    /** 上一帧的光标 {row, col}；null = 隐藏。 */
    public int[] cursor() { return display.cursor; }

    /** isearch 状态（没开 ⇒ null）。 */
    public Isearch isearch() { return isearch; }

    // ══ 给命令用的公开操作（commands 包里的命令只用公开 API）════════════

    /** 撤销当前 buffer 的一组改动；⇒ 有没有可撤的。 */
    public boolean undo() {
        Buffer b = currentBuffer();
        if (b.readOnly) throw new UserError("Buffer is read-only: %s", b.name);
        int p = b.undo.undo(b, point());
        if (p < 0) return false;
        goTo(p);
        b.deactivateMark();
        return true;
    }

    /** 重做被撤销的；⇒ 有没有可重做的。 */
    public boolean redo() {
        Buffer b = currentBuffer();
        if (b.readOnly) throw new UserError("Buffer is read-only: %s", b.name);
        int p = b.undo.redo(b, point());
        if (p < 0) return false;
        goTo(p);
        return true;
    }

    private static final Set<String> VERTICAL = Set.of("next-line", "previous-line");

    /**
     * 竖向移动 n 行，保持目标列（C-n / C-p）。按屏幕行走（line-move-visual，Emacs 默认）；minibuffer、截断模式按逻辑行。
     * ⇒ 撞头了吗。
     */
    public boolean lineMove(int n) {
        Window w = currentWindow();
        View.VLine v = view.vlineAt(w, w.point());
        int col = view.columnInRow(w, w.point());
        Integer goal = VERTICAL.contains(lastCommand) && w.goalCol != null ? w.goalCol : Integer.valueOf(col);
        View.VLine t = view.step(w, v, n);
        int moved = view.distance(w, n > 0 ? v : t, n > 0 ? t : v, Math.abs(n) + 1);
        w.goalCol = goal;
        if (moved < Math.abs(n)) {
            w.setPoint(n > 0 ? w.buffer.length() : 0);
            w.goalCol = goal;
            return true;
        }
        w.setPoint(view.posAtCol(w, t, goal));
        return false;
    }

    /** 一屏的行数减 2（C-v 的 next-screen-context-lines）。 */
    public int pageHeight() { return Math.max(1, textSize(currentWindow())[1] - 2); }

    /** C-l：point 那一行放到窗口第 target 行。 */
    public void recenter(int target) {
        Window w = currentWindow();
        View.VLine pv = view.vlineAt(w, w.point());
        w.setStart(view.vstart(w, view.step(w, pv, -target)));
    }

    /** 命令跑完后再跑 name（M-x 选好的命令带着当时的前缀参数再跑一次）。 */
    public void runAfterCommand(String name, Object prefix) { pendingRun = new Object[]{name, prefix}; }

    /** 上一条可重复的命令（M-x repeat）。 */
    public String repeatableCommand() { return repeatCommand; }

    public String minibufferSelectedCandidate() { return minibufferSelected(); }
    public void minibufferSelect(int delta) { minibufferMoveSelection(delta); }
    public void minibufferHistoryStep(int delta) { if (mb != null) minibufferHistory(delta); }
    public MinibufferRequest activeMinibufferRequest() { return minibufferRequest(); }
    public void setMinibufferInput(String s) { setMiniInput(s); }

    /** 开始 / 结束录键盘宏。 */
    public void startMacro() {
        if (macroRecording != null) throw new UserError("Already defining keyboard macro");
        macroRecording = new ArrayList<>();
        message("Defining kbd macro...");
    }

    public void endMacro() {
        if (macroRecording == null) throw new UserError("Not defining kbd macro");
        List<String> keys = new ArrayList<>(macroRecording);
        for (int i = 0; i < thisKeys.size() && !keys.isEmpty(); i++) keys.removeLast();   // 去掉结束这条宏的那几个键
        lastMacro = keys;
        macroRecording = null;
        message("Keyboard macro defined");
    }

    public void callMacro(int times) {
        if (macroRecording != null) endMacro();
        if (lastMacro == null) throw new UserError("No kbd macro has been defined");
        List<String> m = lastMacro;
        runAfterIdle(() -> playMacro(m, Math.max(1, times)));
    }

    private void runAfterIdle(Runnable r) { afterCommandAction = r; }

    /** describe-key：下一个完整键序列不执行，而是显示它绑的命令（mode = "full" / "brief"）。 */
    public void startDescribeKey(String mode) { describeKeyMode = mode; }
    /** 取走 describe-key 的模式（"full" / "brief"），之后恢复正常派发。 */
    public String takeDescribeKeyMode() { String m = describeKeyMode; describeKeyMode = null; return m; }

    /** kill ring 里从新到旧第 i 条（M-y 用）。 */
    public String kill(int i) { return killRing.isEmpty() ? null : killAt(i); }
    public int yankIndex() { return yankIndex; }
    public void setYankIndex(int i) { yankIndex = i; }

    /** 自动补全的入口：self-insert 之后调（打了触发字符就定时弹框）。 */
    public void afterSelfInsert(String ch) { maybeAutoComplete(ch); }

    /** 光标位置的覆盖层里第一个可点的（按钮）；没有 ⇒ null。 */
    public Overlay buttonAt(int pos) { return currentBuffer().overlayAt(pos, o -> o.action != null); }

    /** 清掉前缀参数、按了一半的前缀键。 */
    public void resetCommandState() { prefixArg = null; pending.clear(); }
}
