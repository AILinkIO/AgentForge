# AgentForge

AI Agent 框架：一个无界面的 agent 运行时（工具、审批、沙盒、会话存盘、子 agent、MCP），外面套行模式命令行与全屏终端两种外壳（桌面 GUI 与 Web 还在计划里）。
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
| bubblewrap / Landlock / seccomp | — | Linux 上 run-command 的沙盒后端；Windows 走 AppContainer |
| JLine3 / Picocli | 3.27 / 4.7 | 行模式终端交互与命令行解析 |
| flexmark / RSyntaxTextArea | 0.64.8 / 4.0.2 | markdown 渲染与代码块高亮（终端侧） |

## 模块

```
AgentForge/
├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
├── bitcask/      libbitcask 的 Java 封装（FFM）：KV、原子批、范围扫描、BitcaskIndex 全文 / 向量检索、
│                 图存储层（邻接表）与 openCypher 查询
├── sandbox/      命令沙盒（bwrap / Landlock + seccomp / Windows AppContainer、审批编排、执行策略、网络代理）
│   └── native/   Landlock / seccomp / 代理桥的原生件源码（:sandbox:buildNative 编译）
├── tui/          终端内核（Emacs 式编辑器：buffer、覆盖层、折叠、窗口、补全、主题、which-key、isearch）
├── tui-agent/    全屏 agent 工作台（样子照 OpenAI Codex 的 TUI），建在 tui + agent-core 上
├── agent-core/   agent 业务层（coxswain agent 的 Java 移植）：AgentRuntime、AgentSession、工具、审批、
│                 模型管理、会话存盘（BitcaskSessionStore）、沙箱执行器、子 agent、MCP、code-mode
└── app-cli/      命令行入口：默认全屏工作台、agent（行模式）、chat、history
```

## 构建

```bash
./gradlew build                                   # 编译 + 全部测试（没指定 libbitcask 时，依赖它的用例跳过）
./gradlew build -Pbitcask.lib=/path/to/libbitcask.so.13.1.1   # 测试用这份 libbitcask
./gradlew :sandbox:buildNative                    # 只编 Landlock / seccomp 原生件（Linux，需要 cc）
./gradlew :app-cli:bootJar -Pbitcask.lib=/path/libbitcask.so   # 打可执行 jar，并把 libbitcask 打进去
./gradlew :tui:installDist                        # 终端内核的演示程序（假会话，tui/build/install/tui/bin/tui）
```

- `-Pbitcask.lib=…`：测试时传给 JVM（`agentforge.bitcask.lib`）；`bootJar` 时把它塞进 jar 的 `/native/<rid>/`，运行时自动解出。
  不打进 jar 也行：运行时设 `AGENTFORGE_BITCASK_LIB=/path/libbitcask.so`。
- `:sandbox:test` 会先跑 `:sandbox:buildNative`，用系统属性 `agentforge.sandbox.native.dir` 指过去。
- 产物：`app-cli/build/libs/agentforge.jar`；根目录的 `./agentforge` 脚本就是 `java --enable-native-access=ALL-UNNAMED -jar` 它
  （从 jar 的**副本**跑：fat jar 按需加载类，直接覆盖会打断正在跑的实例）。

## 运行

```bash
./agentforge                                      # 全屏工作台（默认入口；要真终端）
./agentforge tui                                  # 同上
./agentforge agent                                # 行模式 REPL（管道 / 脚本里用这个）
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

`agent` 与工作台都不经过 Spring，用 agent-core 自己的模型管理，**没有 key 也能启动**（看历史、选模型）。
首次运行时如果一家 provider 都没启用、而环境里有某家的 key，会自动启用那一家（记进 `state/providers.json`）。

### 全屏工作台

样子照 OpenAI Codex 的 TUI：

- **会话头卡片**：版本、模型 · 思考档、工作目录、权限、沙盒、非默认 agent。
- **记录区**：一条一格 —— `›` 你说的话、`•` 回答 / 命令、`Explored`（并起来的只读工具）、改文件的真 diff、计划清单；
  子 agent 那格带活读数（正在做什么、等你什么），跑完收成摘要，各自的结论跟在各自那格下面。
- **状态区**：状态行（`• 正在想 (12s • Esc Esc 中断)`，动词带流光）下面是在跑的子 agent 一行
  （称呼、用时 · 步数、正在做什么；等你审批的写成黄色）。主会话闲着而后台还有子 agent 时照样提示。
- **底栏**：左边是看场合的键位提示，右边是模型 · 思考档 · 上下文剩余 · 用量 · 估价；临时消息几秒内整条顶替提示行。
- **审批 / 提问 / 选择器**都在底栏里顶替输入框，不打断记录区。

每一趟子 agent 有稳定的称呼（`junior #1`）和稳定的颜色（`agent-thread-N`），记录区、状态行、总览、审批面板里用的是同一套。

键位：

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
| 审批面板里 `y` `p` `a` `d` `b` `n` / 数字 / `↑↓ RET` / `?` | 直接答；`?` 展开被收起的详情 |
| 提问面板里 `1-9` `SPC` `↑↓ RET` `e` `x` `Esc` | 选 / 勾 · 确认 · 自己写答案 · 跳过本题 · 整道不答 |
| 子 agent 里 `←` `→` | 在兄弟之间走（输入框空着时） |
| `C-h k` / `C-h b` | 某个键干什么 / 全部键 |

`/` 命令（`/help` 里有全部；工作台另有 `/steer` `/stop` `/copy` `/side` `/theme` `/attach` `/title` 这几件，行模式没有）：

| 命令 | 说明 |
|------|------|
| `/help` | 命令说明 |
| `/model [ref]` | 看 / 换当前模型（目录里没有的 ref 按自定义模型登记，适合网关） |
| `/models` | 可选的模型 |
| `/agents [name]` | agent 总览（工作台：主会话 + 每一趟子 agent；行模式：列出可选的 agent）；给名字 = 换主会话的 agent |
| `/new` | 开一段新对话 |
| `/sessions` | 最近的对话 |
| `/open <id>` | 打开库里的一段对话接着聊 |
| `/search <词>` | 全文检索历史（子串、大小写不敏感，至少 3 个字） |
| `/compact` | 现在压缩上下文 |
| `/steer [话]` | 停掉这一轮，把排着的话连同输入立刻发出去 |
| `/stop` | 停这一轮 |
| `/copy` | 复制最后一段回答 |
| `/side` | 收展侧栏（待办 / 子 agent / MCP / 权限） |
| `/permissions [预设]` | 看权限设置 / 换预设（read-only / auto / full-access） |
| `/approvals <策略>` | 审批策略：untrusted / on-failure / on-request / never / granular |
| `/sandbox <档>` | 沙盒：read-only / workspace-write / danger-full-access |
| `/network on\|off` | 沙盒里能不能出网 |
| `/rules [reload]` | 看已装的执行规则文件 / 重读 |
| `/thinking [档位]` | 看 / 设思考档位 |
| `/mcp` | MCP 服务器状态 |
| `/theme [名字]` | 换主题（不给名字时弹选择器；内置 14 套调色板 + `terminal`） |
| `/attach <路径>` | 附一张图片到下一条消息（PNG / JPEG / GIF / WebP） |
| `/title <文字>` | 给这段对话改名 |
| `/quit` | 退出 |

### 行模式 agent（`agentforge agent`）

JLine REPL：直接打字 = 发给 agent；它忙的时候打字 = 排队（下一次问模型前并进去）；**Ctrl-C** 按停这一轮；**Ctrl-D** 退出。
流式输出（思考过程暗色显示）、工具调用 `⏺` / 结果 `⎿` 行、每轮结束一行用量：`I 输入 / O 输出 · M 上下文占比 · C 缓存命中 · $ 估价`。
clarify 提问：输入序号（多选用逗号）或直接写答案；空行跳过本题，`/skip` 关掉。

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

## agent 与子 agent

一条 agent = 人格（系统提示词 + 工具 + 钉的模型）。内置这几条：

| agent | 干什么 | 工具 |
|-------|--------|------|
| `main` | 跟用户说话的那一个（默认） | 默认那整套（下面「工具」一节列的） |
| `junior` | 轻的、动手的活：按指定的样子改文件、跑命令看结果、顺手查点东西 | 只读那套 + `write-file` / `edit-file` / `replace-form` / `run-command`（**每一笔都过审批闸**） |
| `deep` | 在整个仓库里找东西：按名字找文件、按内容搜代码、回答「X 是在哪儿实现的」 | 只读那几件（`search-text` / `find-files` / `read-file` / `list-dir`） |
| `senior` | 想不清楚的难题：查不出成因的 bug、该怎么改的架构、两条路选哪条 | 只读全套（最贵的一档模型） |
| `vision` | 看图：把截图 / 架构图里的内容读出来，或看着界面截图挑毛病 | 一件工具都没有（图随消息附上） |

主 agent 用 `task` 工具派活：子 agent 自己去看、去读（junior 还能按吩咐改），回来一段结论 —— 中间过程不占主会话的上下文。
参数：`description`（一句话说明）、`prompt`（自足的任务描述，子 agent 看不见上下文）、`subagent_type`、`images`、`task_id`（接着上一趟往下跑）、
`background`（后台跑）、`task`（哪一类活，用于路由）。默认只派一层（子 agent 不能再派）。

`C-c a` 的总览里能看到所有这些：主会话 + 每一趟（含后台的、子 agent 再派的），状态 / 类型 / 模型 / 正在做什么 / 等你什么 / 最近几步。

## 工具

内置那套（顺序固定，给模型看的；顺序影响模型挑哪件）：

- 读：`read-file`、`list-dir`、`search-text`、`find-files`、`find-symbol`、`symbol-refs`、`impact`
- 看 worktree：`worktree-list`
- 写：`write-file`、`edit-file`、`delete-path`；Scheme 专用：`list-forms`、`read-form`、`replace-form`
- 跑：`run-command`（可要求出沙盒 `sandbox_permissions: require_escalated`）
- 批量改：`apply-patch`
- 出网：`web-fetch`、`web-search`（Exa / Parallel）
- 自己的：`memory-list` / `memory-read` / `memory-write`（项目笔记）、`skill`（装一份操作手册）、`todo-write`（计划清单）
- 动 worktree：`worktree-create` / `worktree-remove` / `worktree-reset` / `worktree-enter`
- 主 agent 另有 `clarify`（把选择题摆给用户）、`task`（派活）
- 有 MCP 工具时再加一件 `execute`（code-mode：受限环境里跑一段 Scheme 脚本调 MCP 工具，把十次往返变一次）

写文件 / 删文件 / 出网 / 跑命令 / 动 worktree 是**敏感**的，要过审批闸；被拒的调用也会先记下 diff，所以记录区里画得出真 diff 与真行号。

## 权限与沙盒

照 Codex CLI：**审批策略 × 沙盒策略**决定一切。命令先过执行规则（`.rules`，Codex execpolicy 语法），再进平台沙盒。

| 预设 | 审批 | 沙盒 |
|------|------|------|
| `read-only` | on-request | 全盘只读 |
| `auto`（默认） | on-request | 全盘可读；工作根 + 临时目录 + 额外可写根可写；`.git` 等受保护名仍只读；断网 |
| `full-access` | never | 不进沙盒，改哪儿连哪儿都不问 |

审批策略另有 `untrusted` / `on-failure` / `never` / `granular`；沙盒档另有 `workspace-write`（默认）/ `danger-full-access`。
沙盒后端：Linux 上 bwrap → Landlock（+ seccomp），Windows 上 AppContainer → 受限令牌；**本机没有后端时受限策略下未知命令逐条审批后在本机跑**（启动时明说）。

规则的来源与优先级：内建 → 用户（`<配置目录>/rules`）→ 项目（`<根>/.agentforge/rules`，只能收紧）。
「以后都不再问」追加到用户的 `rules/default.rules`。内建规则管着 `git reset --hard`、`git clean`、`git checkout -- .`、强推这几类会丢东西或改写远端历史的操作。

设计见 [designs/permission-sandbox.md](designs/permission-sandbox.md)。

## 模型与 provider

- **目录**：models.dev 的 `api.json` 下载后缓存在 `<配置目录>/data/models-dev.json`（7 天过期，后台刷新，永不阻塞启动）；
  离线时用旧缓存；一条缓存都没有时退到随包带的一小份。`M-x agent-refresh-catalog` 手动刷。
- **直接认识的**：`anthropic`、`openai`、`deepseek`、`minimax`（各自的 key 环境变量见下表）。
- **自己加的**：`<配置目录>/providers.json` —— 自托管 / 内网网关 / 目录里没有的厂商：

```json
{
  "providers": {
    "litegate": {
      "api": "anthropic",
      "baseUrl": "http://127.0.0.1:4000",
      "apiKeyEnv": "LITEGATE_API_KEY",
      "models": { "MiniMax-M3.1-Flash": { "context": 200000, "maxOutput": 32000 } }
    }
  },
  "model": "litegate/MiniMax-M3.1-Flash"
}
```

写错的那家跳过，原因在启动时说清（不整份作废）。

- **key**：环境变量优先；也可以存进加密凭据库（`auth.json.enc`，AES-256-GCM，密钥跟着配置目录走）。
  `M-x agent-set-key` 存一个；`C-c m` / `/model` 选模型。

## MCP

MCP 客户端在 `agent-core`（stdio 本地子进程 + HTTP 远程两路）：目录每轮现取，服务器挂了时工具照给、报错说人话。
服务器在代码里登记（`McpSpecs.define`），启停等决定落在 `<配置目录>/state/mcp.json`（程序写的，手改会被下一次操作覆盖；token 不在这里）。
`--no-mcp` 关掉。工具名前缀默认 `<服务器名>_`（不许空前缀，也不许 `/`）。

## 笔记、技能、计划清单

- **笔记**（`memory-*` 工具）：`<配置目录>/memory/<项目>/` 下的 markdown，跨会话还在；写笔记不走审批。
- **技能**（`skill` 工具）：一个目录 + 一份 `SKILL.md`（frontmatter 写 name / description，正文是步骤）。
  查找顺序：`$AGENTFORGE_SKILLS` → `<根>/.agentforge/skills` → `<根>/.claude/skills` → `<根>/.agents/skills`
  → `<配置目录>/skills` → `~/.claude/skills` → `~/.agents/skills`（同名先到的赢）。
- **计划清单**（`todo-write` 工具）：每段会话一份（子 agent 各有各的），只在内存里；侧栏里看得到。

## 配置目录与文件

默认 `~/.config/agentforge`（`$AGENTFORGE_HOME` 优先，其次 `$XDG_CONFIG_HOME/agentforge`；Windows 上 `%APPDATA%\agentforge`）：

```
<配置目录>/
├── providers.json      你自己定义的 provider 与默认模型（手写）
├── auth.json.enc       加密凭据库（明文 auth.json 第一次读到会自动搬走并删掉）
├── .auth-key           凭据库的密钥
├── rules/              执行规则（builtin.rules 盖掉内建那套；default.rules 存「以后不再问」）
├── skills/             你自己的 skill
├── memory/             笔记（按项目分目录）
├── state/              程序写的状态：agent.json（上次选的模型 / 权限）、providers.json、mcp.json
└── data/               数据：sessions/（会话库，bitcask：kv + fts）、models-dev.json
```

会话库装不上 libbitcask（或库被占着）时退回内存库，并在启动时明说「本次对话只存在内存里」。

## 环境变量

| 变量 | 用途 |
|------|------|
| `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` | Anthropic key（agent；chat 用 `ANTHROPIC_AUTH_TOKEN`） |
| `ANTHROPIC_BASE_URL` | Anthropic 兼容网关地址（agent 与 chat 都认） |
| `OPENAI_API_KEY` | OpenAI key |
| `DEEPSEEK_API_KEY` / `MINIMAX_API_KEY` | agent 认识的另外两家 |
| `EXA_API_KEY` / `PARALLEL_API_KEY` | web-search 的两家（没有 `EXA_API_KEY` 时用 Parallel） |
| `AGENTFORGE_MODEL` | agent 的默认模型（赢过上次选的） |
| `AGENTFORGE_APPROVAL` / `AGENTFORGE_SANDBOX` | agent 的审批策略 / 沙盒策略（命令行开关优先） |
| `AGENTFORGE_HOME` | agent 的配置目录（默认 `~/.config/agentforge`） |
| `AGENTFORGE_AGENT_PREFS` | 上次选的模型 / 权限那份偏好文件（默认 `state/agent.json`） |
| `AGENTFORGE_PROVIDERS` | 启用过哪几家 provider（默认 `state/providers.json`） |
| `AGENTFORGE_AUTH` / `AGENTFORGE_SECRET_KEY` | 凭据库 / 它的密钥 |
| `AGENTFORGE_NO_KEYRING` | 不碰系统密钥库（密钥落文件） |
| `AGENTFORGE_MODELS_CACHE` | models.dev 目录的缓存位置（默认 `data/models-dev.json`） |
| `AGENTFORGE_SKILLS` / `AGENTFORGE_MEMORY` | 额外的 skill 目录 / 笔记目录 |
| `AGENTFORGE_BITCASK_LIB` | libbitcask.so 的路径（没打进 jar 时） |
| `AGENTFORGE_SANDBOX_NATIVE_DIR` | 沙盒原生件（Landlock / seccomp / 代理桥）的目录（没编时走 bwrap） |
| `AGENTFORGE_WEBSEARCH_PROVIDER` / `AGENTFORGE_WEBSEARCH_URL` | web-search 用哪家 / 打到哪个端点 |
| `AGENTFORGE_DATA_DIR` | chat / history 的数据目录（默认 `./data`） |
| `LLM_PROVIDER` | chat 用哪家：`anthropic`（默认）/ `openai` |

## bitcask（图与查询）

`bitcask` 模块是 libbitcask 的 Java 封装（FFM）：KV（原子批、范围扫描、游标）、`BitcaskIndex`（BM25 全文 / 向量 ANN / RRF 混合）、
图存储层（邻接表布局、顶点与边、块目录外置的布局 2、`upgradeLayout` 单向升级）、openCypher 2024.3 查询
（`MATCH` / `MERGE` / 路径搜索 / `<=>` 向量距离 / `@@` 全文匹配）、时间与空间值类型。
与 clj-bitcask / Erlang bitcask 写的库互相读得懂。`agent-core` 的会话库就建在它上面。

## 阶段计划

1. **核心**（已完成）：bitcask、sandbox、agent-core 三个模块；会话存 bitcask（全文检索）、run-command 进沙盒、审批接到会话闸上。
2. **命令行**（已完成）：行模式 REPL + 全屏工作台（tui / tui-agent），界面照 Codex 的 TUI。
3. **桌面 GUI**：Compose Desktop 外壳，嵌同一个 `AgentRuntime`。
4. **Web**：Spring Boot 后端 + React 前端。

## License

[MIT](LICENSE)
