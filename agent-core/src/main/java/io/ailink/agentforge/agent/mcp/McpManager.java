package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.ToolOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * MCP 客户端：一张服务器表 + 一个通用执行函数。对应 coxswain {@code (coxn agent mcp)}。
 *
 * <h2>形状：每件远端工具一条 stub，stub 只闭包名字</h2>
 * 模型面前每件远端工具一条 {@link AgentTool}（带完整 schema），它的执行体<b>不闭包连接</b>，只闭包
 * "服务器名 + 远端名"，调用时才来这张表里取当时的活连接。于是：
 * <ul>
 *   <li>断开 / 重连都不换工具对象 —— 目录没变时重连后 {@link #tools()} 里是<b>同一批对象</b>，会话那份
 *       工具表不用重推；</li>
 *   <li>服务器挂着时模型调用得到"服务器 X 没有连接，请用户用 /mcp 重连"这种读得懂的失败，而不是
 *       "没有名为 X 的工具"（后者它会换条路瞎试）；</li>
 *   <li>重连、认证、超时全长在一处（{@link #dispatch}）。</li>
 * </ul>
 * <b>failed 的服务器工具照给，disabled 的不给</b>：一句"没连接"比一件凭空消失的工具便宜得多；
 * 停用是"我不要它"。
 *
 * <h2>线程</h2>
 * 连接都在后台虚拟线程上（一台 30 秒才起得来的 {@code npx} 服务器不能卡住调用方）；
 * {@link #connect} 等立刻返回。状态写在本对象的锁下；每次断开 / 重连把这台的代数 +1，迟到的连接结果
 * 发现代数不对就自己关掉，不会覆盖新状态。
 *
 * <h2>宿主要接的</h2>
 * <ul>
 *   <li>{@link #start()} 启动时、{@link #stop()} 退出时；配置变了 {@link #reload()}；</li>
 *   <li>{@link #toolsSupplier()} 交给会话（每轮开始取一份）；{@link McpEvent.Kind#CATALOG} 事件来了把新表推给会话；</li>
 *   <li>{@link #servers()} / {@link #counts()} 给界面；{@link #instructions()} 进系统提示词。</li>
 * </ul>
 */
public final class McpManager implements AutoCloseable {
    /** instructions 每轮都发，截到这么长。 */
    public static final int MAX_INSTRUCTIONS = 2000;

    private final McpSpecs specs;
    private final McpState state;
    private final McpTokens tokens;
    private final List<Server> servers = new ArrayList<>();
    private final List<McpEvent.Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String clientName = "agentforge";
    private volatile String clientVersion = defaultVersion();
    private volatile Function<String, String> env = System::getenv;
    private HttpClient http;

    public McpManager(McpSpecs specs, McpState state, McpTokens tokens) {
        this.specs = specs;
        this.state = state;
        this.tokens = tokens;
    }

    /** 默认：空配置表、{@code stateDir/mcp.json}、内存 token。 */
    public McpManager() { this(new McpSpecs(), McpState.defaultState(), McpTokens.inMemory()); }

    public McpSpecs specs() { return specs; }

    public McpState state() { return state; }

    /** initialize 里报的身份（不给 clientInfo 是协议非法的）。 */
    public void setClientInfo(String name, String version) {
        this.clientName = name;
        this.clientVersion = version == null ? "0" : version;
    }

    /** 测试用：替换 {@code ${VAR}} 的查法。 */
    public void setEnvLookup(Function<String, String> f) { this.env = f == null ? System::getenv : f; }

    public void addListener(McpEvent.Listener l) { listeners.add(l); }

    public void removeListener(McpEvent.Listener l) { listeners.remove(l); }

    // ═══ 一台服务器（内部） ═════════════════════════════════════════════

    private static final class Server {
        final String name;
        McpSpec spec;
        McpStatus status;
        String error;
        McpClient client;            // 活连接；null = 没连上
        RpcTransport pending;        // 正在握手的那条（断开时要能掐掉）
        boolean inFlight;
        int generation;
        List<McpTools.Entry> catalog = List.of();
        List<AgentTool> tools = List.of();
        List<McpTools.Rejected> rejected = List.of();
        String instructions;
        Instant since;
        StdioTransport stdio;        // 本地：取 stderr 尾巴用
        String stderr = "";

        Server(McpSpec spec) {
            this.name = spec.name();
            this.spec = spec;
            this.status = spec.enabled() ? McpStatus.CONNECTING : McpStatus.DISABLED;
        }
    }

    private Server find(String name) {
        for (Server s : servers) if (s.name.equals(name)) return s;
        return null;
    }

    // ═══ 表：按 spec 对齐 ═══════════════════════════════════════════════

    /**
     * 把配置表与 state 合起来对齐服务器表。三条规则按"谁说了算"排：
     * <ol>
     *   <li>配置里定义的 —— 形状它说了算，state 只叠一个 {@code enabled}（反过来的话用户改了配置却不生效）；</li>
     *   <li>state 里有、配置里没有的 —— 界面上加出来的，整条 spec 照单接收（读不出形状的跳过）；</li>
     *   <li>两边都没有 = 不存在（连接收掉）。</li>
     * </ol>
     * 形状变了的整条换掉（屏幕上写着新配置、跑着旧进程是最糟的）。顺序：配置的在前、界面加的在后。
     * 只对齐、不连接（连接由 {@link #connectAll()}）。
     */
    public List<McpServerInfo> syncSpecs() {
        List<McpSpec> effective = effectiveSpecs();
        List<McpClient> toClose = new ArrayList<>();
        synchronized (this) {
            List<Server> next = new ArrayList<>();
            for (McpSpec spec : effective) {
                Server old = find(spec.name());
                if (old != null && old.spec.sameShape(spec)) {
                    old.spec = spec;
                    if (!spec.enabled() && old.status != McpStatus.DISABLED) {
                        detach(old, toClose);
                        old.status = McpStatus.DISABLED;
                        old.error = null;
                    } else if (spec.enabled() && old.status == McpStatus.DISABLED) {
                        old.status = McpStatus.CONNECTING;
                    }
                    next.add(old);
                } else {
                    if (old != null) detach(old, toClose);
                    next.add(new Server(spec));
                }
            }
            for (Server s : servers) if (!next.contains(s)) detach(s, toClose);
            servers.clear();
            servers.addAll(next);
        }
        closeAll(toClose);
        emit(new McpEvent(McpEvent.Kind.CATALOG, "", null));
        return servers();
    }

    private List<McpSpec> effectiveSpecs() {
        List<McpSpec> out = new ArrayList<>();
        for (McpSpec spec : specs.all()) {
            ObjectNode st = state.ref(spec.name());
            JsonNode on = st == null ? null : st.get("enabled");
            out.add(on != null && on.isBoolean() && on.asBoolean() != spec.enabled() ? spec.withEnabled(on.asBoolean()) : spec);
        }
        for (Map.Entry<String, ObjectNode> e : state.entries()) {
            if (specs.ref(e.getKey()).isPresent()) continue;
            try {
                out.add(McpSpec.fromJson(e.getKey(), e.getValue()));
            } catch (IllegalArgumentException ignored) {
                // 手改坏了的一条：跳过，不整份作废
            }
        }
        return out;
    }

    // ═══ 生命周期 ═══════════════════════════════════════════════════════

    /** 启动：对齐 + 全部连接（立刻返回）。 */
    public void start() {
        syncSpecs();
        connectAll();
    }

    /** 配置 / state 变了：重读 state、对齐、把该连的连上。 */
    public void reload() {
        state.reload();
        start();
    }

    public void stop() { shutdown(); }

    @Override
    public void close() { shutdown(); }

    /** 连接所有 connecting / failed / needs-auth 的（needs-auth 也试：用户刚设完 token 按"全部重连"正是想让它再试）。 */
    public void connectAll() {
        for (McpServerInfo s : servers()) {
            if (s.status() == McpStatus.CONNECTING || s.status() == McpStatus.FAILED || s.status() == McpStatus.NEEDS_AUTH) {
                connect(s.name());
            }
        }
    }

    /** 起一台；立刻返回。停用的 / 不存在的回 false；已经连着或正在连回 true（幂等）。 */
    public boolean connect(String name) {
        Server s;
        int gen;
        synchronized (this) {
            s = find(name);
            if (s == null || s.status == McpStatus.DISABLED) return false;
            if (s.client != null || s.inFlight) return true;
            s.status = McpStatus.CONNECTING;
            s.error = null;
            gen = ++s.generation;
            s.inFlight = true;
        }
        emit(new McpEvent(McpEvent.Kind.STATUS, name, McpStatus.CONNECTING));
        startConnect(s, gen);
        return true;
    }

    /** 断开：留着登记、留着工具（调用会回一句"未连接"），只收连接。不发 CATALOG。 */
    public void disconnect(String name) {
        List<McpClient> toClose = new ArrayList<>();
        McpStatus st;
        synchronized (this) {
            Server s = find(name);
            if (s == null) return;
            detach(s, toClose);
            if (s.status != McpStatus.DISABLED) {
                s.status = McpStatus.FAILED;
                s.error = "已断开（用户操作）";
            }
            st = s.status;
        }
        closeAll(toClose);
        emit(new McpEvent(McpEvent.Kind.STATUS, name, st));
    }

    public void reconnect(String name) {
        List<McpClient> toClose = new ArrayList<>();
        Server s;
        int gen;
        synchronized (this) {
            s = find(name);
            if (s == null || s.status == McpStatus.DISABLED) return;   // 停用的要先启用
            detach(s, toClose);
            s.status = McpStatus.CONNECTING;
            s.error = null;
            s.stderr = "";
            gen = ++s.generation;
            s.inFlight = true;
        }
        closeAll(toClose);
        emit(new McpEvent(McpEvent.Kind.STATUS, name, McpStatus.CONNECTING));
        startConnect(s, gen);
    }

    /** 全部重连（停用的除外）。 */
    public void reconnectAll() {
        for (McpServerInfo s : servers()) if (s.status() != McpStatus.DISABLED) reconnect(s.name());
    }

    /**
     * 停用 / 启用，并<b>落盘</b>（跨会话的决定）。停用发 CATALOG（工具真的不给了）。
     * 写不动不抛：那时行为回到"这次生效、下次没了"。
     */
    public void setEnabled(String name, boolean on) {
        List<McpClient> toClose = new ArrayList<>();
        Server s;
        int gen = -1;
        boolean fromConfig = specs.ref(name).isPresent();
        synchronized (this) {
            s = find(name);
            if (s == null) return;
            s.spec = s.spec.withEnabled(on);
            if (on) {
                if (s.status == McpStatus.DISABLED) {
                    s.status = McpStatus.CONNECTING;
                    gen = ++s.generation;
                    s.inFlight = true;
                }
            } else {
                detach(s, toClose);
                s.status = McpStatus.DISABLED;
                s.error = null;
            }
        }
        rememberEnabled(s.spec, fromConfig, on);
        closeAll(toClose);
        if (on) {
            if (gen >= 0) {
                emit(new McpEvent(McpEvent.Kind.STATUS, name, McpStatus.CONNECTING));
                startConnect(s, gen);
            }
        } else {
            emit(new McpEvent(McpEvent.Kind.STATUS, name, McpStatus.DISABLED));
            emit(new McpEvent(McpEvent.Kind.CATALOG, name, null));
        }
    }

    /** 配置定义的只叠一个 enabled（形状归配置）；界面加的整条 spec 写回去，否则一次停用会把命令抹掉。 */
    private void rememberEnabled(McpSpec spec, boolean fromConfig, boolean on) {
        try {
            if (fromConfig) {
                ObjectNode o = AgentTool.JSON.createObjectNode();
                o.put("enabled", on);
                state.set(spec.name(), o);
            } else {
                state.set(spec.name(), spec.withEnabled(on).toJson());
            }
        } catch (RuntimeException ignored) {
            // 写不动不抛
        }
    }

    /** 界面上加一台：与配置走同一条校验（填错当场抛），写 state，对齐并连接。 */
    public void addServer(String name, Map<String, ?> opts) {
        if (specs.ref(name).isPresent()) {
            throw new IllegalArgumentException(name + " 已经在配置里定义过了 —— 要改它请改配置");
        }
        McpSpec spec = McpSpec.of(name, opts);
        state.set(name, spec.withEnabled(true).toJson());
        syncSpecs();
        connect(name);
    }

    /**
     * 删掉界面上加的那一台。配置里定义的删不掉（删了下次启动又回来，比删不掉难受）—— 回 false，
     * 要它别跑就用 {@link #setEnabled}。
     */
    public boolean removeServer(String name) {
        if (specs.ref(name).isPresent()) return false;
        disconnect(name);
        state.remove(name);
        syncSpecs();
        return true;
    }

    /** 收摊：所有连接（含子进程树）收掉。挂在宿主退出那条路上。 */
    public void shutdown() {
        List<McpClient> toClose = new ArrayList<>();
        synchronized (this) {
            for (Server s : servers) detach(s, toClose);
        }
        closeAll(toClose);
    }

    /** 断开一台的连接（锁内调用）：代数 +1、活连接与正在握手的那条都交出去关。 */
    private void detach(Server s, List<McpClient> toClose) {
        s.generation++;
        s.inFlight = false;
        if (s.client != null) toClose.add(s.client);
        if (s.pending != null) toClose.add(new McpClient(s.pending));
        if (s.stdio != null) s.stderr = s.stdio.stderrTail();
        s.client = null;
        s.pending = null;
        s.stdio = null;
    }

    /** 并行关（stdio 收摊最多要等两秒），等全部关完再返回 —— 返回之后子进程确实没了。 */
    private static void closeAll(List<McpClient> cs) {
        if (cs.isEmpty()) return;
        List<Thread> ts = new ArrayList<>();
        for (McpClient c : cs) ts.add(Thread.ofVirtual().start(c::close));
        for (Thread t : ts) {
            try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ═══ 连接 ═══════════════════════════════════════════════════════════

    private void startConnect(Server s, int gen) {
        Thread.ofVirtual().name("mcp-connect-" + s.name).start(() -> connectBody(s, gen));
    }

    /** 整条兜住：连接失败是常态（没装、网断了、token 过期），没人 await 的线程抛出去是看不见的。 */
    private void connectBody(Server s, int gen) {
        McpSpec spec;
        synchronized (this) { spec = s.spec; }
        McpClient c = null;
        try {
            c = spec.kind() == McpSpec.Kind.LOCAL ? connectLocal(s, spec, gen) : connectRemote(s, spec, gen);
            if (c == null) return;
            List<JsonNode> descs = c.listTools(spec.timeoutMs());
            publish(s, gen, c, McpTools.catalog(spec.prefix(), descs));
        } catch (McpException.HttpStatus e) {
            if (c != null) c.close();
            if (e.isAuth()) {
                fail(s, gen, McpStatus.NEEDS_AUTH, "要认证：对面回了 " + e.getMessage()
                        + " —— 请在 /mcp 里给它设一个 token（或在 headers 里给 Authorization）");
            } else {
                fail(s, gen, McpStatus.FAILED, e.getMessage());
            }
        } catch (McpException.Timeout e) {
            if (c != null) c.close();
            fail(s, gen, McpStatus.FAILED, timeoutLine(spec, stderrOf(s)));
        } catch (RuntimeException e) {
            if (c != null) c.close();
            String m = e.getMessage();
            fail(s, gen, McpStatus.FAILED, m == null || m.isBlank() ? "连接失败（" + e.getClass().getSimpleName() + "）" : m);
        }
    }

    private synchronized String stderrOf(Server s) {
        return s.stdio != null ? s.stdio.stderrTail() : s.stderr;
    }

    /**
     * 本地：先确认可执行文件在（话要指得到是哪个命令，而不是一句 spawn failed）→ 起子进程 → 握手。
     * 握手没走完时两种失败说两种话：协议层真报了错 ⇒ 原样念它的话；纯粹等超了 ⇒ 带自救指引的那句。
     */
    private McpClient connectLocal(Server s, McpSpec spec, int gen) {
        Path cwd = spec.cwd() == null ? null : Path.of(spec.cwd());
        List<String> argv = resolveCommand(spec.command(), cwd);
        if (argv == null) {
            fail(s, gen, McpStatus.FAILED, "找不到可执行文件 " + spec.command().get(0) + "（PATH 里没有）—— 装了吗？路径写对了吗？");
            return null;
        }
        EnvRefs.Expanded<Map<String, String>> envx = EnvRefs.expandAll(spec.env(), env);
        if (!envx.missing().isEmpty()) {
            fail(s, gen, McpStatus.FAILED, "这几个环境变量没设：" + String.join("、", envx.missing()) + "（env 里用 ${名字} 引用的）");
            return null;
        }
        StdioTransport[] holder = new StdioTransport[1];
        StdioTransport t;
        try {
            t = StdioTransport.start(argv, cwd, envx.value(), code -> gone(s, holder[0], code));
        } catch (IOException e) {
            fail(s, gen, McpStatus.FAILED, "起不了 " + argv.get(0) + "：" + e.getMessage());
            return null;
        }
        holder[0] = t;
        if (!attachPending(s, gen, t, t)) {
            t.close();
            return null;
        }
        McpClient c = new McpClient(t);
        watchListChanged(s, c);
        try {
            c.initialize(clientName, clientVersion, spec.timeoutMs());
        } catch (McpException.Timeout e) {
            String tail = t.stderrTail();
            c.close();
            fail(s, gen, McpStatus.FAILED, timeoutLine(spec, tail));
            return null;
        } catch (McpException.RpcError e) {
            c.close();
            fail(s, gen, McpStatus.FAILED, "握手失败：" + e.getMessage());
            return null;
        } catch (McpException.Closed e) {
            // 握手没走完子进程就没了：stdout 的 EOF 可能比 stderr 先到，给 stderr 一点时间收齐
            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            String tail = t.stderrTail();
            c.close();
            fail(s, gen, McpStatus.FAILED, "握手失败：" + goneLine(e.getMessage(), tail));
            return null;
        }
        return c;
    }

    private McpClient connectRemote(Server s, McpSpec spec, int gen) {
        EnvRefs.Expanded<String> url = EnvRefs.expand(spec.url(), env);
        EnvRefs.Expanded<Map<String, String>> hs = EnvRefs.expandAll(spec.headers(), env);
        List<String> missing = new ArrayList<>(url.missing());
        missing.addAll(hs.missing());
        if (!missing.isEmpty()) {
            // ⚠️ 单独说：展成空串发出去换来的是一个 401，读起来像"要认证"，与"忘了 export"是两件事。
            fail(s, gen, McpStatus.FAILED, "这几个环境变量没设：" + String.join("、", missing) + "（headers / url 里用 ${名字} 引用的）");
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.value());
            if (uri.getScheme() == null || !uri.getScheme().startsWith("http")) throw new IllegalArgumentException("不是 http(s) 地址");
        } catch (IllegalArgumentException e) {
            fail(s, gen, McpStatus.FAILED, "URL 写得不对：" + url.value() + "（" + e.getMessage() + "）");
            return null;
        }
        McpClient c = McpClient.connectHttp(http(), uri, authHeaders(spec, hs.value()), clientName, clientVersion,
                spec.timeoutMs());
        if (!attachPending(s, gen, c.transport(), null)) {
            c.close();
            return null;
        }
        watchListChanged(s, c);
        return c;
    }

    private synchronized HttpClient http() {
        if (http == null) http = HttpTransport.newClient();
        return http;
    }

    private synchronized boolean attachPending(Server s, int gen, RpcTransport t, StdioTransport stdio) {
        if (s.generation != gen) return false;
        s.pending = t;
        if (stdio != null) s.stdio = stdio;
        return true;
    }

    /** 服务器说目录变了（tools/list_changed）⇒ 后台重取一次。 */
    private void watchListChanged(Server s, McpClient c) {
        c.transport().onNotification(m -> {
            if (!"notifications/tools/list_changed".equals(m)) return;
            Thread.ofVirtual().start(() -> {
                int gen;
                McpSpec spec;
                synchronized (this) {
                    if (s.client != c) return;
                    gen = s.generation;
                    spec = s.spec;
                }
                try {
                    publish(s, gen, c, McpTools.catalog(spec.prefix(), c.listTools(spec.timeoutMs())));
                } catch (RuntimeException ignored) {
                    // 下次重连再说
                }
            });
        });
    }

    /**
     * 发布目录。⭐ <b>目录没变就把老的 stub 原样留着</b>：重连不该动会话里那份工具表；
     * 工具集真的变了才发 CATALOG。
     */
    private void publish(Server s, int gen, McpClient c, McpTools.Catalog cat) {
        boolean same;
        synchronized (this) {
            if (s.generation != gen) {
                // 迟到的结果（这期间被断开 / 重连 / 停用了）：自己收掉
                Thread.ofVirtual().start(c::close);
                return;
            }
            same = !s.tools.isEmpty() && cat.entries().equals(s.catalog);
            if (!same) {
                List<AgentTool> stubs = new ArrayList<>();
                for (McpTools.Entry e : cat.entries()) stubs.add(stub(s.name, e));
                s.tools = List.copyOf(stubs);
                s.catalog = cat.entries();
            }
            s.rejected = cat.rejected();
            s.client = c;
            s.pending = null;
            s.inFlight = false;
            s.instructions = c.instructions();
            s.status = McpStatus.CONNECTED;
            s.error = null;
            s.since = Instant.now();
        }
        emit(new McpEvent(McpEvent.Kind.STATUS, s.name, McpStatus.CONNECTED));
        if (!same) emit(new McpEvent(McpEvent.Kind.CATALOG, s.name, null));
    }

    private void fail(Server s, int gen, McpStatus status, String why) {
        synchronized (this) {
            if (s.generation != gen) return;
            if (s.stdio != null) s.stderr = s.stdio.stderrTail();
            s.client = null;
            s.pending = null;
            s.stdio = null;
            s.inFlight = false;
            s.status = status;
            s.error = why;
        }
        emit(new McpEvent(McpEvent.Kind.STATUS, s.name, status));
    }

    /** 子进程没了（读线程回来了）。只在它本来是连着的、而且还是这一条连接时改状态。 */
    private void gone(Server s, StdioTransport t, int code) {
        String why;
        synchronized (this) {
            if (t == null || s.client == null || s.client.transport() != t || s.status != McpStatus.CONNECTED) return;
            s.stderr = t.stderrTail();
            s.client = null;
            s.stdio = null;
            s.status = McpStatus.FAILED;
            why = goneLine(code >= 0 ? "子进程退出了（退出码 " + code + "）" : "子进程退出了", s.stderr);
            s.error = why;
        }
        emit(new McpEvent(McpEvent.Kind.STATUS, s.name, McpStatus.FAILED));
    }

    /** 话要带上退出码与 stderr；一个字都没说的时候要给下一步。 */
    static String goneLine(String head, String tail) {
        return head + (tail != null && !tail.isBlank() ? "。它最后说：" + tail
                : "，而且一个字都没往 stderr 写。手动跑一遍那条命令多半就看见了");
    }

    /** 超时那句话要带自救指引，不是死讯：{@code npx -y …} 第一次要下载，30 秒常常不够。 */
    static String timeoutLine(McpSpec spec, String tail) {
        return "握手超时（等了 " + spec.timeoutMs() + " ms）。第一次跑 npx 之类的命令要下载，多半就是慢：把 'timeout-ms 调大，"
                + "或者先在终端里手动跑一次让它进缓存。" + (tail != null && !tail.isBlank() ? "服务器的 stderr：" + tail : "");
    }

    // ═══ 认证 ═══════════════════════════════════════════════════════════

    private static String tokenKey(String name) { return "mcp:" + name; }

    public void setToken(String name, String token) { tokens.set(tokenKey(name), token); }

    public void forgetToken(String name) { tokens.remove(tokenKey(name)); }

    public boolean hasToken(String name) {
        try { return tokens.get(tokenKey(name)).isPresent(); } catch (RuntimeException e) { return false; }
    }

    /**
     * 这一台要发的头 = 配置里写的 + 存着的 token。⚠️ 配置里已经写了同名的头（不分大小写）就不覆盖：
     * 那是他现在的意思，一个存了半年的 token 悄悄盖掉它，症状是"明明改了配置却还是 401"。
     */
    private Map<String, String> authHeaders(McpSpec spec, Map<String, String> base) {
        Map<String, String> out = new LinkedHashMap<>(base);
        Optional<String> tok;
        try { tok = tokens.get(tokenKey(spec.name())); } catch (RuntimeException e) { tok = Optional.empty(); }
        String hdr = spec.authHeader();
        if (tok.isEmpty() || hdr == null || base.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(hdr))) return out;
        String scheme = spec.authScheme();
        out.put(hdr, scheme != null && !scheme.isEmpty() ? scheme + " " + tok.get() : tok.get());
        return out;
    }

    // ═══ 通用执行函数 ═══════════════════════════════════════════════════

    private AgentTool stub(String server, McpTools.Entry e) {
        String remote = e.remoteName();
        String desc = e.description().isBlank() ? "（MCP 服务器 " + server + " 的工具 " + remote + "）" : e.description();
        return AgentTool.builder(e.localName())
                .description(desc)
                .rawSchema(e.schema())
                .readOnly(e.readOnly())
                .concurrencySafe(e.readOnly())
                .sensitive(!e.readOnly())
                .handler((env, args) -> dispatch(server, remote, args.raw(), env.cancel()))
                .build();
    }

    /** 真正执行：按名字现查活连接。没连上抛一句给模型看的话（它下一步该告诉用户去重连，而不是换参数重试）。 */
    ToolOutput dispatch(String server, String remote, JsonNode args, CancelToken cancel) {
        McpClient c;
        long timeout;
        synchronized (this) {
            Server s = find(server);
            c = s == null ? null : s.client;
            if (c == null) throw new ToolException(whyNot(server, s));
            timeout = s.spec.callTimeoutMs();
        }
        try {
            return McpTools.toOutput(c.callTool(remote, args, timeout, cancel));
        } catch (McpException.RpcError e) {
            throw new ToolException("MCP 服务器 " + server + " 报错：" + e.getMessage());
        } catch (McpException.Timeout e) {
            throw new ToolException("MCP 服务器 " + server + " 的 " + remote + " 超时（" + timeout + " ms 没有应答）");
        } catch (McpException.HttpStatus e) {
            if (e.isAuth()) {
                synchronized (this) {
                    Server s = find(server);
                    if (s != null && s.client == c) {
                        s.status = McpStatus.NEEDS_AUTH;
                        s.error = "要认证：对面回了 " + e.getMessage();
                    }
                }
                emit(new McpEvent(McpEvent.Kind.STATUS, server, McpStatus.NEEDS_AUTH));
                throw new ToolException("MCP 服务器 " + server + " 需要认证（对面回了 401/403）—— 请用户在 /mcp 里给它设一个 token");
            }
            throw new ToolException("MCP 服务器 " + server + " 出错：" + e.getMessage());
        } catch (McpException.Closed e) {
            throw new ToolException("MCP 服务器 " + server + " 的连接断了（" + e.getMessage() + "）—— 请用户用 /mcp 重连");
        } catch (McpException e) {
            throw new ToolException("MCP 服务器 " + server + " 出错：" + e.getMessage());
        }
    }

    private static String whyNot(String name, Server s) {
        if (s == null) return "没有叫 " + name + " 的 MCP 服务器（用户可能刚把它删了）";
        return switch (s.status) {
            case CONNECTING -> "MCP 服务器 " + name + " 还在连接中，等一下再试";
            case DISABLED -> "MCP 服务器 " + name + " 已被用户停用 —— 请他用 /mcp 启用";
            case NEEDS_AUTH -> "MCP 服务器 " + name + " 需要认证（对面回了 401/403） —— 请用户在 /mcp 里给它设一个 token";
            default -> "MCP 服务器 " + name + " 没有连接" + (s.error != null ? "（" + s.error + "）" : "")
                    + " —— 请用户用 /mcp 重连；在那之前这台服务器的工具都用不了";
        };
    }

    // ═══ 给宿主 ═════════════════════════════════════════════════════════

    /** 这一轮该给模型哪几件：所有没停用的服务器拼起来（failed 的照给，见类注释）。 */
    public synchronized List<AgentTool> tools() {
        List<AgentTool> out = new ArrayList<>();
        for (Server s : servers) if (s.status != McpStatus.DISABLED) out.addAll(s.tools);
        return out;
    }

    /** 给会话的取数口（coxswain 的 {@code agent-extra-tools} thunk）：现调，不是快照。 */
    public Supplier<List<AgentTool>> toolsSupplier() { return this::tools; }

    /** 还有没有正在连的（会话第一轮可以等一下：连上之后工具表会变，而工具在前缀最前面）。 */
    public synchronized boolean connecting() {
        return servers.stream().anyMatch(s -> s.status == McpStatus.CONNECTING);
    }

    /** 等到没有正在连的，或者超时。返回是否已经定下来。 */
    public boolean awaitSettled(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (connecting()) {
            if (System.nanoTime() > deadline) return false;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** 状态栏那一格。{@code attention} = 有 failed / needs-auth（都要用户动手）。 */
    public record Counts(int connected, int total, boolean attention) { }

    public synchronized Counts counts() {
        int up = 0;
        boolean bad = false;
        for (Server s : servers) {
            if (s.status == McpStatus.CONNECTED) up++;
            if (s.status == McpStatus.FAILED || s.status == McpStatus.NEEDS_AUTH) bad = true;
        }
        return new Counts(up, servers.size(), bad);
    }

    public synchronized List<McpServerInfo> servers() {
        List<McpServerInfo> out = new ArrayList<>();
        for (Server s : servers) out.add(info(s));
        return out;
    }

    public synchronized Optional<McpServerInfo> server(String name) {
        Server s = find(name);
        return s == null ? Optional.empty() : Optional.of(info(s));
    }

    private McpServerInfo info(Server s) {
        return new McpServerInfo(s.name, s.spec, s.status, s.error, s.tools, s.rejected, s.instructions, s.since,
                s.stdio != null ? s.stdio.stderrTail() : s.stderr, specs.ref(s.name).isPresent(), hasToken(s.name));
    }

    /** 已连上且给了 instructions 的服务器：名字 → 文本（截到 {@link #MAX_INSTRUCTIONS}）。 */
    public synchronized Map<String, String> instructions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Server s : servers) {
            if (s.status == McpStatus.CONNECTED && s.instructions != null) {
                String t = s.instructions;
                out.put(s.name, t.length() > MAX_INSTRUCTIONS ? t.substring(0, MAX_INSTRUCTIONS) + "…（已截）" : t);
            }
        }
        return out;
    }

    /** 本地子进程的 pid（测试 / 排查用；没连着为空）。 */
    public synchronized Optional<Long> pid(String name) {
        Server s = find(name);
        return s == null || s.stdio == null ? Optional.empty() : Optional.of(s.stdio.pid());
    }

    private void emit(McpEvent e) {
        for (McpEvent.Listener l : listeners) {
            try { l.onEvent(e); } catch (RuntimeException ignored) { }
        }
    }

    // ═══ 可执行文件 ═════════════════════════════════════════════════════

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * 起进程之前先确认可执行文件在；回可以直接交给 ProcessBuilder 的 argv，找不到回 null。
     * Windows 上试 PATHEXT（{@code npx} 实际是 {@code npx.cmd}；不试的话每一条服务器都会被判成找不到），
     * 而 {@code .cmd} / {@code .bat} 要经 {@code cmd.exe /c} 才起得来。
     */
    static List<String> resolveCommand(List<String> argv, Path cwd) {
        String cmd = argv.get(0);
        Path found = null;
        boolean hasSep = cmd.indexOf('/') >= 0 || (WINDOWS && cmd.indexOf('\\') >= 0);
        List<String> exts = new ArrayList<>(List.of(""));
        if (WINDOWS) {
            String pe = System.getenv("PATHEXT");
            for (String e : (pe == null ? ".COM;.EXE;.BAT;.CMD" : pe).split(";")) if (!e.isEmpty()) exts.add(e);
        }
        if (hasSep) {
            Path p = Path.of(cmd);
            if (!p.isAbsolute() && cwd != null && Files.exists(cwd.resolve(p))) found = cwd.resolve(p);
            else found = firstExisting(p.toString(), exts);
        } else {
            String path = System.getenv("PATH");
            for (String dir : (path == null ? "" : path).split(java.io.File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                found = firstExisting(Path.of(dir, cmd).toString(), exts);
                if (found != null) break;
            }
        }
        if (found == null) return null;
        List<String> out = new ArrayList<>(argv);
        String f = found.toString().toLowerCase();
        if (WINDOWS && (f.endsWith(".cmd") || f.endsWith(".bat"))) {
            out.set(0, found.toString());
            out.addAll(0, List.of("cmd.exe", "/c"));
        }
        return out;
    }

    private static Path firstExisting(String base, List<String> exts) {
        for (String e : exts) {
            Path p = Path.of(base + e);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String defaultVersion() {
        String v = McpManager.class.getPackage().getImplementationVersion();
        return v == null ? "0" : v;
    }
}
