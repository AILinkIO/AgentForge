# AgentForge

AI Agent Framework，基于 Spring Boot 构建的 CLI 工具。

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
│   ├── entity/                         # JPA 实体
│   │   ├── ChatMessageEntity.java      # 消息实体
│   │   └── DailySummaryEntity.java     # 每日总结实体
│   ├── repository/                     # 数据访问层
│   │   ├── ChatMessageRepository.java
│   │   └── DailySummaryRepository.java
│   ├── service/                        # 业务逻辑层
│   │   └── ChatHistoryService.java
│   ├── llm/                            # LLM Provider 抽象与实现
│   │   ├── LlmProvider.java            # Provider 接口
│   │   ├── LlmProviderConfig.java      # Provider 自动选择
│   │   ├── claude/                     # Claude 实现
│   │   └── openai/                     # OpenAI 实现
│   └── template/
│       └── PromptRenderer.java         # Jinja2 模板渲染
├── src/main/resources/
│   ├── application.yml
│   └── prompts/
│       └── summarizer.md               # 总结 Prompt 模板
└── src/test/
```

## License

[MIT](LICENSE)
