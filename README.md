# AgentForge

AI Agent Framework，基于 Spring Boot 构建的 CLI 工具。
这个项目是AI Agentic应用开发的配套代码，[课程目录](https://zhuanlan.zhihu.com/p/1999462417701303527)，[购买地址](https://wx.zsxq.com/group/28885111454111)

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | 运行环境 |
| Spring Boot | 3.5.0 | 应用框架（Console 模式） |
| Spring Data JPA | 3.5.0 | 数据持久化 |
| H2 Database | 2.3.232 | 嵌入式数据库（纯Java，无JNI警告） |
| JLine3 | 3.27.0 | 增强终端交互（历史命令、彩色输出） |
| Picocli | 4.7.6 | 命令行参数解析 |
| Jinjava | 2.8.3 | Prompt 模板引擎 |
| Spring WebFlux | 3.4.3 | 异步 HTTP 客户端（SSE 流式） |
| Maven | 3.9+ | 项目构建与依赖管理 |

## 功能特性

- **交互式对话**：作为知识问答助手，支持上下文记忆
- **Tool Calling 支持**：LLM 可调用工具完成任务
- **增强终端体验**：命令历史（上下键）、ANSI 彩色输出
- **历史消息管理**：持久化存储所有对话记录，按日期查询
- **每日总结**：自动生成每日对话要点总结
- **双 Provider 支持**：支持 Claude 和 OpenAI

## 环境变量

使用前需配置 LLM 服务的环境变量：

```bash
# Claude（二选一即可）
export ANTHROPIC_AUTH_TOKEN="your-api-key"
export ANTHROPIC_BASE_URL="https://api.anthropic.com"   # 可选，默认官方地址

# OpenAI
export OPENAI_API_KEY="your-api-key"

# 指定使用哪个 Provider（可选，不设置则根据 API Key 自动检测）
export LLM_PROVIDER=claude   # 或 openai
```

也可以将变量写入文件后 source：

```bash
source ~/token.env
```

## 编译打包

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包为可执行 JAR
mvn package -DskipTests
```

## 运行

### 交互式对话

```bash
# 启动交互式对话（默认作为知识问答助手）
./agentforge chat

# 指定自定义系统提示词
./agentforge chat --system "你是一个Python编程专家"

# 交互功能
- 上下方向键：浏览历史命令
- Ctrl+D：退出对话

# 内置命令
:help, :h   - 显示帮助
:history     - 显示最近消息
:clear, :c  - 清除对话上下文
:summary     - 显示今日总结
:quit, :q   - 退出对话
```

### 历史消息管理

```bash
# 列出最近消息
./agentforge history --list

# 按日期查看消息
./agentforge history --date 2026-02-18

# 查看消息统计
./agentforge history --count

# 生成今日总结
./agentforge history --summary

# 查看所有每日总结
./agentforge history --all-summaries

# 查看帮助
./agentforge --help
./agentforge chat --help
./agentforge history --help
```

## Tool Calling（工具调用）

AgentForge 支持 LLM 调用工具来完成任务。内置工具会自动注册，LLM 会根据用户输入决定是否调用工具。

### 示例：使用计算器

```
> 帮我计算 23 * 45 + 12

🔧 正在调用工具: calculator...

[calculator] 1037

根据计算结果，23 * 45 + 12 = 1037。
```

### 内置工具

| 工具名称 | 说明 |
|----------|------|
| `calculator` | 数学计算器，支持基本运算符（+、-、*、/）和 JavaScript Math 对象的所有方法 |

### Calculator 工具详解

**功能特性：**
- 支持基本算术运算：`+`（加）、`-`（减）、`*`（乘）、`/`（除）
- 支持括号优先级：`(2 + 3) * 4`
- 支持所有 JavaScript Math 方法：
  - `Math.sqrt(x)` - 平方根
  - `Math.pow(x, y)` - 幂运算
  - `Math.sin(x)` - 正弦
  - `Math.cos(x)` - 余弦
  - `Math.tan(x)` - 正切
  - `Math.log(x)` - 自然对数
  - `Math.PI` - 圆周率
  - `Math.E` - 自然常数
  - 以及更多 Math 对象方法...

**参数格式：**
```json
{
  "expression": "数学表达式字符串"
}
```

**使用示例：**

```bash
# 基本运算
> 计算 23 * 45 + 12
🔧 正在调用工具: calculator...
[calculator] 1037

# 幂运算
> 计算 2 的 10 次方
🔧 正在调用工具: calculator...
[calculator] 1024

# 平方根
> 计算 16 的平方根
🔧 正在调用工具: calculator...
[calculator] 4

# 复杂表达式
> 计算 (Math.PI * 2) + Math.sin(Math.PI / 2)
🔧 正在调用工具: calculator...
[calculator] 7.283185307179586

# 混合运算
> 计算 Math.pow(3, 2) + Math.sqrt(16)
🔧 正在调用工具: calculator...
[calculator] 13
```

**安全限制：**
- 只允许数字、运算符、括号、空格和 `Math.` 函数
- 防止恶意代码注入

### 自定义工具

实现 `Tool` 接口并添加 `@Component` 注解即可自动注册：

```java
@Component
public class MyCustomTool implements Tool {

    @Override
    public String name() {
        return "my_tool";  // 工具名称（唯一标识）
    }

    @Override
    public String description() {
        return "工具描述（发送给 LLM）";
    }

    @Override
    public JsonNode inputSchema() {
        // 返回 JSON Schema 定义参数结构
        String schema = """
            {
                "type": "object",
                "properties": {
                    "param1": {
                        "type": "string",
                        "description": "参数说明"
                    }
                },
                "required": ["param1"]
            }
            """;
        return new ObjectMapper().readTree(schema);
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String param1 = arguments.get("param1").asText();
        // 执行工具逻辑
        return ToolResult.success(/* toolCallId */, "执行结果");
    }
}
```

### 工具执行流程

```
用户输入 → LLM 判断是否需要工具
    ↓
需要工具 → 返回 tool_use
    ↓
ToolExecutor.execute() → 执行工具
    ↓
工具结果返回 LLM → 生成最终回答
```

## 数据存储

- 数据库文件：`data/agentforge.mv.db`（H2）
- 所有对话消息永久保存，不删除
- 每日总结需手动触发生成

## 项目结构

```
AgentForge/
├── pom.xml
├── src/main/java/io/ailink/agentforge/
│   ├── AgentForgeApplication.java      # Spring Boot 启动类
│   ├── AppRunner.java                  # CLI 入口，子命令分发
│   ├── cli/
│   │   ├── ChatCommand.java            # chat 子命令
│   │   └── HistoryCommand.java         # history 子命令
│   ├── config/                         # Spring 配置
│   ├── persistence/
│   │   ├── entity/                     # JPA 实体
│   │   │   ├── ChatMessageEntity.java
│   │   │   └── DailySummaryEntity.java
│   │   └── repository/                 # 数据访问层
│   │       ├── ChatMessageRepository.java
│   │       └── DailySummaryRepository.java
│   ├── service/                        # 业务逻辑层
│   │   └── ChatHistoryService.java
│   ├── llm/                            # LLM Provider 抽象与实现
│   │   ├── LlmProvider.java            # Provider 接口
│   │   ├── LlmProviderConfig.java      # Provider 自动选择
│   │   ├── dto/                        # 通用 DTO
│   │   │   ├── ChatRequest.java
│   │   │   ├── ChatMessage.java
│   │   │   └── ChatResponse.java
│   │   ├── claude/                     # Claude 实现
│   │   │   ├── ClaudeProvider.java
│   │   │   └── dto/
│   │   └── openai/                     # OpenAI 实现
│   │       ├── OpenAiProvider.java
│   │       └── dto/
│   ├── tool/                           # Tool Calling 支持
│   │   ├── Tool.java                   # 工具接口
│   │   ├── ToolDefinition.java         # 工具定义 DTO
│   │   ├── ToolCall.java               # 工具调用 DTO
│   │   ├── ToolResult.java             # 工具结果 DTO
│   │   ├── ToolRegistry.java           # 工具注册表
│   │   ├── ToolExecutor.java           # 工具执行器
│   │   └── builtin/                    # 内置工具
│   │       └── CalculatorTool.java     # 数学计算器
│   ├── template/
│   │   └── PromptRenderer.java         # Jinja2 模板渲染
│   └── ui/                             # 终端 UI
│       ├── TerminalManager.java
│       ├── ScreenDrawer.java
│       └── DisplayMessage.java
├── src/main/resources/
│   ├── application.yml
│   └── prompts/
│       └── summarizer.md               # 总结 Prompt 模板
└── src/test/
```

## License

[MIT](LICENSE)
