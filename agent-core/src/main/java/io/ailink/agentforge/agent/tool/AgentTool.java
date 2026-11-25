package io.ailink.agentforge.agent.tool;

import io.ailink.agentforge.agent.model.Block;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 一件工具：声明（名字、说明、参数）+ 性质（只读 / 并发安全 / 敏感 / 可暂存）+ 执行体。
 *
 * <p>对应 coxswain {@code define-tool} 与 {@code (options (read-only #t) (sensitive #t) …)}。
 * 实现 Spring AI 的 {@link ToolCallback}：声明经 Spring AI 发给模型；但**执行不走 Spring AI**
 * （会话关掉 internalToolExecutionEnabled，由自己的循环跑 gate / 并发 / 按停），所以
 * {@link #call(String)} 只在脱离会话直接用时才有意义。
 *
 * <ul>
 *   <li>{@code readOnly} —— 不改任何东西；子 agent 只拿这一类。</li>
 *   <li>{@code concurrencySafe} —— 同一轮里相邻的几件可以并发跑。</li>
 *   <li>{@code sensitive} —— 改文件 / 跑命令 / 出网；按审批策略 × 沙盒策略决定要不要人点头（见 {@code approval.ToolApprovals}）。</li>
 *   <li>{@code stageable} —— 写盘可以先攒在 staging vfs 里，整轮结束一次过目。</li>
 * </ul>
 */
public final class AgentTool implements ToolCallback {

    @FunctionalInterface
    public interface Handler {
        /** 返回 String / Block / List&lt;Block&gt; / ToolOutput；失败抛 {@link ToolException}。 */
        Object run(ToolEnv env, Args args) throws Exception;
    }

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private final String description;
    private final List<Param> params;
    private final ObjectNode schema;
    private final boolean readOnly;
    private final boolean concurrencySafe;
    private final boolean sensitive;
    private final boolean stageable;
    private final Predicate<Workspace> enabled;
    private final Handler handler;
    private volatile Workspace defaultWorkspace;

    private AgentTool(Builder b) {
        this.name = Objects.requireNonNull(b.name);
        this.description = Objects.requireNonNull(b.description, "description");
        this.params = List.copyOf(b.params);
        this.schema = b.rawSchema != null ? b.rawSchema : buildSchema(b.params);
        this.readOnly = b.readOnly;
        this.concurrencySafe = b.concurrencySafe;
        this.sensitive = b.sensitive;
        this.stageable = b.stageable;
        this.enabled = b.enabled;
        this.handler = Objects.requireNonNull(b.handler, "handler");
        if (readOnly && sensitive) throw new IllegalStateException("工具 " + name + " 既 read-only 又 sensitive");
    }

    public static Builder builder(String name) { return new Builder(name); }

    public String name() { return name; }

    public String description() { return description; }

    public List<Param> params() { return params; }

    public ObjectNode schema() { return schema; }

    public boolean readOnly() { return readOnly; }

    public boolean concurrencySafe() { return concurrencySafe; }

    public boolean sensitive() { return sensitive; }

    public boolean stageable() { return stageable; }

    public boolean enabled(Workspace ws) { return enabled == null || enabled.test(ws); }

    /** 换掉执行体（MCP 重连、宿主重建时）；性质不变。 */
    public AgentTool withHandler(Handler h) {
        Builder b = toBuilder();
        b.handler = h;
        return b.build();
    }

    public Builder toBuilder() {
        Builder b = new Builder(name).description(description);
        b.params.addAll(params);
        b.rawSchema = schema;
        b.readOnly = readOnly;
        b.concurrencySafe = concurrencySafe;
        b.sensitive = sensitive;
        b.stageable = stageable;
        b.enabled = enabled;
        b.handler = handler;
        return b;
    }

    /** 补默认值；不在这里做类型校验（那是 {@code ToolArgsChecker} 的事）。 */
    public ObjectNode withDefaults(ObjectNode input) {
        ObjectNode o = input == null ? JsonNodeFactory.instance.objectNode() : input.deepCopy();
        for (Param p : params) {
            if (!p.required() && (!o.has(p.name()) || o.get(p.name()).isNull()) && p.defaultValue() != null) {
                o.set(p.name(), JSON.valueToTree(p.defaultValue()));
            }
        }
        return o;
    }

    /** 执行：异常一律变成 is_error 结果（文字给模型看）。按停时抛出的取消异常往上传。 */
    public ToolOutput execute(ToolEnv env, ObjectNode input) {
        try {
            return ToolOutput.of(handler.run(env, new Args(withDefaults(input))));
        } catch (io.ailink.agentforge.agent.model.CancelToken.CancelledException e) {
            throw e;
        } catch (ToolException e) {
            return ToolOutput.error(e.getMessage());
        } catch (Exception e) {
            String m = e.getMessage();
            return ToolOutput.error(e.getClass().getSimpleName() + (m == null ? "" : ": " + m));
        }
    }

    // ── Spring AI ToolCallback ──────────────────────────────────────────

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema.toString()).build();
    }

    /** 宿主可以设一个默认工作区，使脱离会话的 {@link #call(String)} 可用。 */
    public AgentTool bindWorkspace(Workspace ws) { this.defaultWorkspace = ws; return this; }

    @Override
    public String call(String toolInput) {
        Workspace ws = defaultWorkspace;
        if (ws == null) ws = new Workspace(java.nio.file.Path.of("."));
        JsonNode n = toolInput == null || toolInput.isBlank() ? JsonNodeFactory.instance.objectNode() : JSON.readTree(toolInput);
        ToolOutput out = execute(ToolEnv.of(ws), n instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode());
        return out.text();
    }

    private static ObjectNode buildSchema(List<Param> params) {
        ObjectNode s = JsonNodeFactory.instance.objectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        ArrayNode req = JsonNodeFactory.instance.arrayNode();
        for (Param p : params) {
            ObjectNode ps = p.schema() instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
            if (!ps.has("type")) ps.put("type", p.type());
            if ("array".equals(p.type()) && !ps.has("items")) ps.putObject("items").put("type", "string");
            ps.put("description", p.description());
            if (p.enumValues() != null) {
                ArrayNode e = ps.putArray("enum");
                p.enumValues().forEach(e::add);
            }
            if (p.defaultValue() != null) ps.set("default", JSON.valueToTree(p.defaultValue()));
            props.set(p.name(), ps);
            if (p.required()) req.add(p.name());
        }
        if (!req.isEmpty()) s.set("required", req);
        return s;
    }

    public static final class Builder {
        private final String name;
        private String description;
        private final List<Param> params = new ArrayList<>();
        private ObjectNode rawSchema;
        private boolean readOnly, concurrencySafe, sensitive, stageable;
        private Predicate<Workspace> enabled;
        private Handler handler;

        private Builder(String name) { this.name = name; }

        public Builder description(String d) { this.description = d; return this; }

        public Builder param(Param p) { params.add(p); return this; }

        public Builder required(String name, String type, String desc) { return param(Param.required(name, type, desc)); }

        public Builder optional(String name, String type, String desc, Object dflt) { return param(Param.optional(name, type, desc, dflt)); }

        /** 直接给完整 JSON schema（MCP 工具用）；参数表此时只用于补默认值。 */
        public Builder rawSchema(ObjectNode s) { this.rawSchema = s; return this; }

        public Builder readOnly() { this.readOnly = true; return this; }

        public Builder concurrencySafe() { this.concurrencySafe = true; return this; }

        public Builder sensitive() { this.sensitive = true; return this; }

        public Builder stageable() { this.stageable = true; return this; }

        public Builder readOnly(boolean v) { this.readOnly = v; return this; }

        public Builder sensitive(boolean v) { this.sensitive = v; return this; }

        public Builder concurrencySafe(boolean v) { this.concurrencySafe = v; return this; }

        public Builder enabled(Predicate<Workspace> p) { this.enabled = p; return this; }

        public Builder handler(Handler h) { this.handler = h; return this; }

        public AgentTool build() { return new AgentTool(this); }
    }

    /** 方便：一段文本块。 */
    public static Block text(String s) { return new Block.Text(s); }
}
