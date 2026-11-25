# AgentForge

AI Agent 框架：一个无界面的 agent 运行时（工具、审批、沙箱、会话存盘、子 agent、MCP），外面套命令行 / 桌面 / Web 三种外壳。
这个项目是 AI Agentic 应用开发的配套代码，[课程目录](https://zhuanlan.zhihu.com/p/1999462417701303527)，[购买地址](https://wx.zsxq.com/group/28885111454111)

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 25 | bitcask / sandbox 走 FFM（`java.lang.foreign`） |
| Gradle | 9.8（Kotlin DSL，自带 wrapper） | 多模块构建 |
| Spring Boot | 4.1 | `app-cli` 的应用框架（无 web 容器） |
| Spring AI | 2.0 | 模型调用（Anthropic / OpenAI） |
| Jackson | 3（`tools.jackson.databind`） | JSON |
| libbitcask | 13.1.x | KV + 全文 / 向量检索（C++，经 FFM 调用） |
| bubblewrap / Landlock | — | Linux 上 run-command 的沙箱后端 |
| JLine3 / Picocli | 3.27 / 4.7 | 终端交互与命令行解析 |

## 模块

```
AgentForge/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
├── bitcask/      libbitcask 的 Java 封装（FFM）：KV、原子批、范围扫描、BitcaskIndex 全文 / 向量检索
├── sandbox/      命令沙箱（bwrap / Landlock + seccomp / Windows AppContainer、审批编排、网络代理）
│   └── native/   Landlock / seccomp / 代理桥的原生件源码（:sandbox:buildNative 编译）
├── tui/          终端内核（Emacs 式编辑器：buffer、覆盖层、折叠、窗口、补全、主题、which-key）
├── tui-agent/    全屏 agent 工作台（样子照 OpenAI Codex 的 TUI），建在 tui + agent-core 上
├── agent-core/   agent 业务层（coxswain agent 的 Java 移植）：AgentRuntime、AgentSession、工具、审批、
│                 模型管理、会话存盘（BitcaskSessionStore）、沙箱执行器、子 agent、MCP
└── app-cli/      命令行入口：agent（新）、chat、history
```

- **agent-core** 的会话库默认在 `<配置目录>/data/sessions`（bitcask：`kv/` 存对话与消息，`fts/` 是只收可见文本的全文索引）；
  libbitcask 装不上时退回内存库，并在启动时明说"本次对话不会留下来"。
- **权限与沙盒**照 Codex CLI：审批策略（`untrusted` / `on-failure` / `on-request` / `never`）× 沙盒策略（`read-only` /
  `workspace-write` / `danger-full-access`），默认 `on-request` + `workspace-write`（全盘可读、工作根和临时目录可写、`.git` 只读、断网）。
  命令先过执行规则（`.rules`，Codex execpolicy 语法），再进平台沙盒（bwrap / Landlock）；本机没有后端时未知命令逐条审批后在本机跑。
  设计见 [designs/permission-sandbox.md](designs/permission-sandbox.md)。

## 构建

```bash
./gradlew build                                   # 编译 + 全部测试（没指定 libbitcask 时，agent-core 里依赖它的用例跳过）
./gradlew build -Pbitcask.lib=/path/to/libbitcask.so.13.1.1   # 测试用这份 libbitcask
./gradlew :sandbox:buildNative                    # 只编 Landlock / seccomp 原生件（Linux，需要 cc）
./gradlew :app-cli:bootJar -Pbitcask.lib=/path/to/libbitcask.so   # 打可执行 jar，并把 libbitcask 打进去
```

- `-Pbitcask.lib=…`：测试时传给 JVM（`agentforge.bitcask.lib`）；`bootJar` 时把它塞进 jar 的 `/native/<rid>/`，运行时自动解出。
  不打进 jar 也行：运行时设 `AGENTFORGE_BITCASK_LIB=/path/libbitcask.so`。
- `:sandbox:test` 会先跑 `:sandbox:buildNative`，用系统属性 `agentforge.sandbox.native.dir` 指过去。
- 产物：`app-cli/build/libs/agentforge.jar`；根目录的 `./agentforge` 脚本就是 `java --enable-native-access=ALL-UNNAMED -jar` 它。

## 运行

### agent：交互式编码 agent

```bash
./agentforge agent                                # 当前目录为工作根
./agentforge agent --root ~/proj --model anthropic/claude-sonnet-4-5
./agentforge agent --agent deep                   # 换 agent
./agentforge agent -s read-only                   # 沙盒：read-only / workspace-write（默认）/ danger-full-access
./agentforge agent -a untrusted                   # 审批：untrusted / on-failure / on-request（默认）/ never / granular
./agentforge agent --full-auto                    # on-request + workspace-write
./agentforge agent --preset read-only             # 预设：read-only / auto / full-access
./agentforge agent --network --add-dir ~/data     # 沙盒里可联网、另加一个可写目录
./agentforge agent --yolo                         # = --dangerously-bypass-approvals-and-sandbox：不审批、不进沙盒
./agentforge agent --no-mcp                       # 不连 MCP 服务器
```

`agent` 不经过 Spring，用 agent-core 自己的模型管理，**没有 key 也能启动**（看历史、选模型）。首次运行时如果一家 provider 都没启用、
而环境里有某家的 key，会自动启用那一家（记进 `state/providers.json`）。

REPL 里：

- 直接打字 = 发给 agent；它忙的时候打字 = 排队（下一次问模型前并进去）；**Ctrl-C** 按停这一轮；**Ctrl-D** 退出。
- 流式输出（思考过程暗色显示）、工具调用 `⏺` / 结果 `⎿` 行、每轮结束一行用量：`I 输入 / O 输出 · M 上下文占比 · C 缓存命中 · $ 估价`。
- 审批：`y` 放行、`a` 本会话不再问、`p` 以后都不再问（命令写进 `<配置目录>/rules/default.rules`，主机写 `network_rule`）、
  `d` 不做接着干（可接一句话交给模型，如 `d 先别动 build`）、`b` 以后都拦下这个主机、`n` 停下这一轮。
- clarify 提问：输入序号（多选用逗号）或直接写答案；空行跳过本题，`/skip` 关掉。

| 命令 | 说明 |
|------|------|
| `/help` | 命令说明 |
| `/model [ref]` | 看 / 换当前模型（目录里没有的 ref 按自定义模型登记，适合网关） |
| `/models` | 可选的模型 |
| `/agents [name]` | 列出 agent / 换 agent |
| `/new` | 开一段新对话 |
| `/sessions` | 最近的对话 |
| `/open <id>` | 打开库里的一段对话接着聊 |
| `/search <词>` | 全文检索历史（子串、大小写不敏感，至少 3 个字） |
| `/compact` | 现在压缩上下文 |
| `/permissions [预设]` | 看权限设置 / 换预设（read-only / auto / full-access） |
| `/approvals <策略>` | 审批策略：untrusted / on-failure / on-request / never / granular |
| `/sandbox <档>` | 沙盒：read-only / workspace-write / danger-full-access |
| `/network on\|off` | 沙盒里能不能出网 |
| `/rules [reload]` | 看已装的执行规则文件 / 重读 |
| `/thinking [档位]` | 看 / 设思考档位 |
| `/mcp` | MCP 服务器状态 |
| `/quit` | 退出 |

### 全屏工作台（不带子命令时的默认入口）

```bash
./agentforge                                      # 开关与 agent 相同：./agentforge -a untrusted --no-mcp …
```

样子照 OpenAI Codex 的 TUI：开头一张会话头卡片（模型、目录、权限、沙盒），记录里一条一格（`›` 你说的话、`•` 回答 / 命令 /
`Explored` / 改文件的 diff / 计划清单），底栏是状态行（`• 正在想 (12s • Esc Esc 中断)`）、排队的话、输入框和一行键位提示
（右边是模型 · 上下文剩余 · 用量）。审批、提问、选择器都在底栏里顶替输入框。键位精简过，其余都走 `/` 命令与 `M-x`：

| 键 | 作用 |
|----|------|
| `RET` / `C-j`（`M-RET`） | 发送（忙时排队）/ 换行 |
| `↑` `↓` | 按行移动，到边翻历史 |
| `Esc` | 闲时清空输入；跑着时按两次停；审批时 = 拒绝；提问时 = 不答 |
| `C-c C-c` | 停这一轮（闲着时提示怎么退出） |
| `C-c m` / `C-c k` / `C-c p` | 选模型 / 思考档位 / 权限预设 |
| `C-c l` | 对话列表（第一项开一段新对话） |
| `C-x o` | 记录区 ↔ 输入框 |
| `C-x C-c` | 退出（这一轮在跑时先问一句） |
| `PgUp` / `PgDn` | 翻记录 |
| `/` · `@` · `M-x` | 命令表 · 引用文件 · 全部命令 |
| 记录区里 `TAB` `n` `p` `RET` `M-w` `q` | 折叠 · 跳块 · 进入 / 打开 · 复制这一块 · 回输入框 |
| `C-s` / `C-r` | 在记录里搜索 |
| 审批面板里 `y` `p` `a` `d` `b` `n` / 数字 / `↑↓ RET` | 直接答 |
| `C-h k` / `C-h b` | 某个键干什么 / 全部键 |

除了下面 `agent` 的那些命令，工作台另有：`/steer [话]`（停掉这一轮，把排着的话连同输入立刻发出去）、`/stop`、`/copy`（复制最后一段回答）、
`/agents`（换 agent、看各趟子 agent、在子 agent 里时回主对话；子 agent 里输入框空着时 `←` `→` 在兄弟之间走）、`/theme`（主题选择器，
挪到哪套先换上看看）、`/side`（侧栏：待办 / 子 agent / MCP / 权限）、`/attach`、`/title`。

### chat / history：原来的问答助手（Spring AI）

```bash
./agentforge chat                                 # 交互式问答（:help 看内置命令）
./agentforge chat --system "你是一个Python编程专家"
./agentforge history --list                       # 最近消息
./agentforge history --date 2026-02-18            # 按日期
./agentforge history --count                      # 统计
./agentforge history --summary                    # 生成今日总结
./agentforge history --all-summaries              # 全部每日总结
```

chat / history 走 Spring AI 自动配置（需要 `ANTHROPIC_AUTH_TOKEN` 或 `OPENAI_API_KEY`），历史存在 bitcask 库
`$AGENTFORGE_DATA_DIR/history`（默认 `./data/history`）。内置一件 `calculator` 工具（Spring AI `@Tool`），LLM 会按需调用。

## 环境变量

| 变量 | 用途 |
|------|------|
| `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` | Anthropic key（agent；chat 用 `ANTHROPIC_AUTH_TOKEN`） |
| `ANTHROPIC_BASE_URL` | Anthropic 兼容网关地址（agent 与 chat 都认） |
| `OPENAI_API_KEY` | OpenAI key |
| `DEEPSEEK_API_KEY` / `MINIMAX_API_KEY` | agent 认识的另外两家 |
| `AGENTFORGE_MODEL` | agent 的默认模型（赢过上次选的） |
| `AGENTFORGE_SANDBOX` | agent 的沙箱策略（`workspace-write` / `read-only` / `danger-full-access`；`--sandbox` 优先） |
| `AGENTFORGE_HOME` | agent 的配置目录（默认 `~/.config/agentforge`：`state/` 选择与偏好、`data/` 会话库与模型目录、加密凭据库） |
| `AGENTFORGE_BITCASK_LIB` | libbitcask.so 的路径（没打进 jar 时） |
| `AGENTFORGE_DATA_DIR` | chat / history 的数据目录（默认 `./data`） |
| `LLM_PROVIDER` | chat 用哪家：`anthropic`（默认）/ `openai` |

key 也可以存进 agent 的加密凭据库（`auth.json.enc`），环境变量优先。

## 阶段计划

1. **核心**（已完成）：bitcask、sandbox、agent-core 三个模块；会话存 bitcask（全文检索）、run-command 进沙箱、审批接到会话闸上。
2. **命令行**（进行中）：现在的 `agent` 是最小但完整的 JLine REPL；下一步照 coxswain main 分支的 TUI 整个重写。
3. **桌面 GUI**：Compose Desktop 外壳，嵌同一个 `AgentRuntime`。
4. **Web**：Spring Boot 后端 + React 前端。

## License

[MIT](LICENSE)
