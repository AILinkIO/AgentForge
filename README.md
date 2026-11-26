# AgentForge

AI Agent 框架：一个无界面的 agent 运行时（工具、审批、沙箱、会话存盘、子 agent、MCP），外面套命令行（全屏 TUI）与 Web 两种外壳。
这个项目是 AI Agentic 应用开发的配套代码，[课程目录](https://zhuanlan.zhihu.com/p/1999462417701303527)，[购买地址](https://wx.zsxq.com/group/28885111454111)

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 25 | bitcask / sandbox 走 FFM（`java.lang.foreign`） |
| Gradle | 9.8（Kotlin DSL，自带 wrapper） | 多模块构建 |
| Spring Boot | 4.1 | `app-cli` 的应用框架（无 web 容器）；`app-web` 的后端（Servlet 容器用 **Jetty 12.1**） |
| React + Vite + shadcn/ui | 19 / 8 / Tailwind 4 | `app-web/frontend`：网页端（版式照 shadcnuikit dashboard） |
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
├── app-cli/      命令行入口：agent（新）、chat、history
└── app-web/      网页端：Spring Boot + Jetty 后端（REST + SSE，包一层 AgentRuntime）
    └── frontend/ React 19 + Vite + shadcn/ui 前端，构建产物打进 jar 的 static/
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
- 网页端：`./gradlew :app-web:bootJar -Pbitcask.lib=…` → `app-web/build/libs/agentforge-web.jar`（会先在 `app-web/frontend`
  里 `npm ci && npm run build`，需要 Node 20+）；只改后端时加 `-Pweb.skipFrontend=true` 跳过前端。

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
`Explored` / 改文件的 diff / 计划清单），底栏是状态行（`• 正在想 (12s • Esc Esc 中断)`）、在跑的子 agent 一个一行（带色称呼 `junior #1`、用时 · 步数、正在做什么）、
排队的话、输入框和一行键位提示（右边是模型 · 上下文剩余 · 用量；临时消息几秒内整条顶替提示行）。审批、提问、选择器都在底栏里顶替输入框。键位精简过，其余都走 `/` 命令与 `M-x`：

| 键 | 作用 |
|----|------|
| `RET` / `C-j`（`M-RET`） | 发送（忙时排队）/ 换行 |
| `↑` `↓` | 按行移动，到边翻历史 |
| `Esc` | 闲时清空输入；跑着时按两次停；审批时 = 拒绝；提问时 = 不答 |
| `C-c C-c` | 停这一轮（闲着时提示怎么退出） |
| `C-c m` / `C-c k` / `C-c p` | 选模型 / 思考档位 / 权限预设 |
| `C-c l` | 对话列表（第一项开一段新对话） |
| `C-c a` | agent 总览（主会话 + 每一趟子 agent，含后台的；`RET` 走进去看它的记录，`Tab` 筛选，`s` 换主会话的 agent；再按一次收起） |
| `C-x o` | 记录区 ↔ 输入框 |
| `C-x C-c` | 退出（这一轮在跑时先问一句） |
| `PgUp` / `PgDn` | 翻记录 |
| `/` · `@` · `M-x` | 命令表 · 引用文件 · 全部命令 |
| 记录区里 `TAB` `n` `p` `RET` `M-w` `q` | 折叠 · 跳块 · 进入 / 打开 · 复制这一块 · 回输入框 |
| `C-s` / `C-r` | 在记录里搜索 |
| 审批面板里 `y` `p` `a` `d` `b` `n` / 数字 / `↑↓ RET` | 直接答 |
| `C-h k` / `C-h b` | 某个键干什么 / 全部键 |

除了下面 `agent` 的那些命令，工作台另有：`/steer [话]`（停掉这一轮，把排着的话连同输入立刻发出去）、`/stop`、`/copy`（复制最后一段回答）、
`/agents`（同 `C-c a`，agent 总览；`/agents 名字` 换主会话的 agent；子 agent 里输入框空着时 `←` `→` 在兄弟之间走）、`/theme`（主题选择器，
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

### Web：网页端

```bash
./agentforge-web                                  # 打开 http://127.0.0.1:8787，在网页上添加项目
./agentforge-web --agentforge.web.root=/path/to/project      # 可选：启动时顺手把这个目录登记成项目
./agentforge-web --agentforge.web.preset=read-only           # 全局权限开关同 agent：preset / approval / sandbox / network / add-dirs
```

- **项目**（照 Codex 桌面端 / ChatGPT Projects）：一个名字 + 一个或几个服务器上的**源文件夹**（网页上的文件夹选择器挑，
  可逐级点进去、敲路径、新建文件夹），外加可选的**项目指令**（拼进该项目每段会话的系统提示）、**默认模型**、**权限预设**。
  存在配置目录的 `projects.json`。侧栏按项目列出会话；「新聊天」页输入框上方选机器 / 项目 / 源文件夹，发第一句话时才建会话，
  工作根就是选的那个文件夹，项目的其余源文件夹在 workspace-write 下也可写。对话记着它属于哪个项目、哪个文件夹，
  重新打开回到原处；移除项目不动文件、不删对话（归到「未归类」）。

- **模型来源与凭证**（照 LiteGate）：模型来自两处——**models.dev 目录**（选中一家、有 key ⇒ 它目录里能调工具的模型全进池子）
  和 **providers.json**（自建网关 / 内网服务，模型逐条列出）。「凭证」页列出每一家的协议、端点、key 来路（环境变量 / 加密存储，只显示末 4 位），
  可「从 models.dev 选择」一键添加，或「手动填写」（写回 providers.json，文件里别的内容不动）。「模型」页可同步 models.dev（后台跑、
  显示进度；认 `HTTPS_PROXY` / `NO_PROXY`、要 gzip、只在 60 秒没数据时才算断、失败自动重试并说出原因）、从镜像地址同步、
  或导入一份 api.json；服务器启动时目录没下过 / 超过 7 天会自动后台同步一次（`--agentforge.web.catalog-auto-sync=false` 关掉）。
  「设为默认」在 providers.json 写了 `model` 时会一并改写那一项（否则改了也被配置盖住），且不动开着的会话。
- 页面：新聊天（选项目 / 文件夹）、概览（统计卡、开着的会话、运行环境、最近对话）、会话（流式输出、Codex 式工具行、子 agent 卡、审批卡、提问卡、
  计划与上下文用量侧栏）、Agents 一览、历史对话（过滤 / 全文搜索）、模型（默认模型、provider key）、权限与沙盒、工具与 MCP；
  ⌘K 命令面板；界面设置里可换配色预设 / 明暗 / 圆角 / 缩放 / 侧栏样式。
- 实时：`GET /api/events`（SSE）推会话事件，前端据此重取快照；流式文字按 epoch + 偏移拼接。
- 安全：缺省只听 `127.0.0.1`，并校验 Host 头（挡 DNS rebinding）；改东西的请求要带 `X-AgentForge: 1`（挡 CSRF）。
  对外开放（`--server.address=0.0.0.0`）时必须带访问令牌：`--agentforge.web.token=…`，不给就随机生成并打在日志里，
  浏览器第一次打开 `/?token=…` 换成 HttpOnly cookie。
- 前端开发：先起后端，再 `cd app-web/frontend && npm run dev`（Vite 把 `/api` 代理到 `AGENTFORGE_WEB_URL`，缺省 8787）。
- 端到端测试（Playwright + 真模型，用本机的 Google Chrome）：`app-web/frontend/e2e/run-e2e.sh`。它建临时配置目录与工作根、
  起后端（untrusted + workspace-write，没有项目）、跑 27 条用户路径（在网页上加项目、多文件夹项目与项目指令、手动添加凭证并用它真聊一轮、同步 models.dev / 导入目录 / 从目录添加凭证、对话、工具、审批批准 / 拒绝 / 快捷键、提问、计划、Markdown、按停、
  子 agent、附件、改标题、压缩、历史搜索 / 打开 / 删除、各设置页、⌘K、主题、窄屏），跑完全部清掉。缺省用 `~/env/minimax.env`
  里的 `MINIMAX_API_KEY` 和 `minimax/MiniMax-M3.1-Flash`；换模型设 `E2E_ENV` / `E2E_PROVIDER` / `E2E_BASE` / `E2E_MODEL`。

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
2. **命令行**（已完成）：行模式 REPL（`agent`）+ 全屏 TUI 工作台（照 coxswain main 分支的 TUI 内核重写）。
3. **Web**（已完成第一版）：Spring Boot + Jetty 后端 + React / Vite / shadcn 前端（不做桌面 GUI）。

## License

[MIT](LICENSE)
