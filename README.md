# AgentForge

AI Agent Framework，基于 Spring Boot 构建的 CLI 工具。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | 运行环境 |
| Spring Boot | 3.4.3 | 应用框架（Console 模式） |
| Picocli | 4.7.6 | 命令行参数解析 |
| Jinjava | 2.8.3 | Prompt 模板引擎 |
| Spring WebFlux | 3.4.3 | 异步 HTTP 客户端（SSE 流式） |
| Maven | 3.9+ | 项目构建与依赖管理 |

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

```bash
# 翻译为英文（默认）
./agentforge translate "你好世界"

# 翻译为中文
./agentforge translate -t zh "Good morning"

# 指定 Provider
LLM_PROVIDER=openai ./agentforge translate "你好世界"

# 查看帮助
./agentforge --help
./agentforge translate --help
```

也可以直接用 `java -jar`：

```bash
java -jar target/agentforge.jar translate "你好世界"
java -jar target/agentforge.jar translate -t zh "Hello World"
```

## 项目结构

```
AgentForge/
├── pom.xml
├── agentforge                          # 启动脚本
├── src/main/java/io/ailink/agentforge/
│   ├── AgentForgeApplication.java      # Spring Boot 启动类
│   ├── AppRunner.java                  # CLI 入口，子命令分发
│   ├── cli/
│   │   └── TranslateCommand.java       # translate 子命令
│   ├── config/                         # Spring 配置
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
│       └── translator.md               # 翻译 Prompt 模板
└── src/test/
```

## License

[MIT](LICENSE)
