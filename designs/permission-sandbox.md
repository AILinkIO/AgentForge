# 权限、审批与沙盒（参照 Codex CLI）

> 状态：已实现（sandbox / agent-core / app-cli）。参照的 Codex 源码：`codex-rs` main（4bad6d78e9，2026-10），
> 主要是 `protocol`、`core/src/exec_policy.rs`、`core/src/safety.rs`、`core/src/tools/{orchestrator,sandboxing}.rs`、
> `execpolicy/`、`shell-command/`、`utils/approval-presets`、`tui/src/bottom_pane/approval_overlay.rs`、`codex-mcp`。

## 1. 目标

AgentForge 原来的权限模型是从 coxswain 搬来的：一个「审批开 / 关」开关（默认关）、按「参数单行串 + `*` 通配」记的
"以后都放行"规则（存在会话库里，按项目根）、再加几条启发式（`.env` 永远问、`rm` 一族与 `.git/` 内部标出种类）。
沙盒是另一条线：run-command 进 bwrap / Landlock，沙盒拦下后再问一次"出沙盒重跑吗"。两套东西各管一半，规则只认字符串，
"开关关着"时除了沙盒什么都不拦。

这次改造把两条线合成 Codex 的一套：**审批策略 × 沙盒策略**决定一切，命令由**执行规则（execpolicy）**和命令安全启发式判，
写文件由**可写范围**判，出网由**网络开关 + 主机规则**判，用户的决定统一成 **ReviewDecision**。

## 2. Codex 的模型（摘要）

### 2.1 两个正交的旋钮

| 审批策略 `AskForApproval` | 含义 |
|---|---|
| `untrusted`（UnlessTrusted） | 只有规则明确放行的命令免问（上游 2026-08 退役了「已知安全命令」白名单，见 §6）；沙盒拦下可问重跑 |
| `on-failure`（已并入 on-request） | 旧版：一律先在沙盒里跑，拦下再问重跑 |
| `on-request`（默认） | 受限文件系统下，模型显式申请出沙盒（`sandbox_permissions: require_escalated`）才问；沙盒拦下不问，把原因回给模型 |
| `granular{sandbox_approval, rules, …}` | 同 on-request，但每类提示可单独关（关 = 自动拒） |
| `never` | 从不问；要问的一律拒，靠沙盒兜底 |

| 沙盒策略 `SandboxPolicy` | 含义 |
|---|---|
| `read-only` | 全盘只读 |
| `workspace-write{writable_roots, network_access, exclude_tmpdir_env_var, exclude_slash_tmp}` | 全盘可读；工作根、`/tmp`、`$TMPDIR`、额外可写根可写；`.git` / `.agents` / `.codex` 等受保护名在可写根里仍只读；网络默认断 |
| `danger-full-access` | 不进沙盒 |
| `external` | 已在外部沙盒里 |

预设（`utils/approval-presets`）：`read-only` = on-request + read-only；`auto`（Default）= on-request + workspace-write；
`full-access` = never + danger-full-access。命令行：`-s/--sandbox`、`-a/--ask-for-approval`、`--full-auto`、
`--dangerously-bypass-approvals-and-sandbox`（`--yolo`）、`--add-dir`。

### 2.2 执行规则（execpolicy）

Starlark 写的 `.rules` 文件：

```starlark
prefix_rule(pattern = ["git", ["push", "fetch"]], decision = "prompt",
            justification = "…", match = ["git push origin"], not_match = ["git status"])
network_rule(host = "api.github.com", protocol = "https", decision = "allow")   # allow / prompt / deny
host_executable(name = "git", paths = ["/usr/bin/git"])
```

- 模式按词逐位匹配前缀；每位可以是备选列表；首位的备选展开成多条规则（按程序名分桶），其余位不做笛卡尔展开。
- 判决 `allow < prompt < forbidden`，多条命中取最严。`match` / `not_match` 是加载时自检的例子。
- 绝对路径程序没有精确命中时可回退到 basename 那一桶（有 `host_executable` 时只认列出的路径）。
- 加载层：系统 / 用户（`~/.codex/rules/*.rules`）/ 项目（`.codex/rules`，项目受信才加载）。AgentForge：内建 / 用户 / 项目（只收紧）。
- 修订：用户选「以后以 X 开头的命令不再问」⇒ 往 `~/.codex/rules/default.rules` 追加 `prefix_rule(pattern=[…], decision="allow")`（加锁、去重）。

### 2.3 一条命令怎么判（`create_exec_approval_requirement_for_command`）

1. `bash -lc "…"` 能拆成**纯字面命令**（只有 `&& || ; |`，没有替换 / 重定向 / 通配 / 控制流）就逐条求值，否则整条当一条。
2. 每条先看规则；没有规则命中按启发式（`render_decision_for_unmatched_command`）：
   危险命令（`rm -f` 一族，穿透 sudo / env / trap / 任意 shell 语法）⇒ prompt（never ⇒ forbidden）；
   never ⇒ allow；untrusted ⇒ prompt；on-request：不受限 ⇒ allow，受限时模型申请出沙盒才 prompt。
3. `forbidden` ⇒ 拒（带理由）；`prompt` ⇒ 要审批（审批策略不许问 ⇒ 拒；区分「规则要求的提示」与「沙盒提示」）；
   `allow` ⇒ 免审批，**每一段都被规则明确放行**时连沙盒都不上（bypass）。
4. 同时给出修订建议：模型提的 `prefix_rule`（不在黑名单 `BANNED_PREFIX_SUGGESTIONS`、没有规则已命中、加上它后每段都放行）
   或第一条启发式命中的命令。

编排（`ToolOrchestrator`）：审批 → 选沙盒 → 第一次尝试（显式升级 / bypass ⇒ 不进沙盒）→ 拒判（关键字 / 退出码）→
审批策略许（`wants_no_sandbox_approval`：untrusted、granular+sandbox）就问「不带沙盒重跑」（本次已批过 ⇒ 免问）→ 重跑。

### 2.4 写文件（`assess_patch_safety`）

空补丁 ⇒ 拒；untrusted ⇒ 问；全部目标都在可写范围内（且有平台沙盒兜底硬链接）⇒ 自动放；否则审批策略不许问 ⇒ 拒
（"只读沙盒" / "越界" 两种理由），许 ⇒ 问。

### 2.5 网络

沙盒网络开关之外，`network-proxy` 做主机级的 allow / deny（域名匹配、仅 GET 的 limited 模式、托管代理）；`network_rule` 编译成
allow / deny 两张表。审批面板上可选「只这一次 / 本会话放行此主机 / 以后放行 / 以后拦下」。

### 2.6 用户的决定与审批面板

`ReviewDecision`：`Approved` / `ApprovedForSession` / `ApprovedExecpolicyAmendment{prefix}` / `NetworkPolicyAmendment{allow|deny}` /
`ApprovedMcpPolicyAmendment` / `Denied{rejection}` / `Abort`。TUI 选项与键：`y` 是 · `p` 以后以 X 开头的不再问 · `a` 本会话不再问 ·
`d` 否，继续 · `n`/Esc 否，告诉 Codex 怎么做（停下这一轮）。补丁：是 / 本会话这些文件不再问 / 否。
MCP 工具：需要审批（看 annotations）时 never 且全盘可写 ⇒ 放，never 其他 ⇒ 拒，否则问（可「以后都放行」）。

## 3. 改造前后对照

| AgentForge 改造前（coxswain） | Codex | 改造后 |
|---|---|---|
| 审批开关 `gate` on/off（默认关），`/gate` | `AskForApproval` | `ApprovalPolicy`（untrusted / on-failure / on-request / granular / never），默认 on-request；`-a`、`/approvals` |
| 沙盒三档 `SandboxMode`（只管 run-command） | `SandboxPolicy` | 同三档 + 网络开关 + 额外可写根（`PermissionSettings`）；同一份策略同时管子进程与进程内文件工具；`-s`、`--network`、`--add-dir`、`/sandbox`、`/network` |
| — | 预设 | `ApprovalPreset`：read-only / auto / full-access；`--preset`、`--full-auto`、`--yolo`、`/permissions` |
| "以后都放行" = 记 `工具 + 首词 *` 模式（会话库 `perm/<根>`） | execpolicy 修订 | 命令：`prefix_rule` 追加到 `<配置目录>/rules/default.rules`；主机：`network_rule`；其他工具：prefs 的 `approved_tools`；本会话不再问：内存 |
| 规则只认参数字符串通配 | `.rules`（前缀词、备选、判决、理由、自检例子） | 移植 `.rules` 子集（`prefix_rule` / `network_rule` / `host_executable`，变量与 `+`），内建 → 用户 → 项目三层 |
| `rm` 一族、`git clean` 启发式（只在开关开着时标种类） | 危险命令启发式 + 规则 | `CommandSafety.dangerousMatch`（rm -f 一族，穿透 shell 语法）+ 内建规则（`git reset --hard` / `git clean` / 强推 / `git checkout -- .` 要求审批） |
| `.git/` 内部启发式 | 受保护名在可写根里只读 | 同 Codex：`.git` / `.agents` / `.aws` 与宿主目录 `.agentforge` 只读 ⇒ 写它们走审批 |
| `.env` 永远问 | 无（只有 deny-read 条目） | **保留**（`SecretFiles`），never 下直接拒；见 §5.3 |
| 删软链 / 越界删除 / 悬空链：永远问，可选"连内容删" | 写路径按可写范围判 | 按**真实目标**判：链接本身在根里 ⇒ 自动放（只删链接）；落到根外 ⇒ 按越界写走审批；确认格（`Deletion`）照旧防 TOCTOU |
| 沙盒拦下 ⇒ 永远问"出沙盒重跑"（`SANDBOX_ESCALATION`） | 只在 untrusted / granular 下问；on-request 回给模型 | 同 Codex，外加 on-failure；拦截说明里告诉模型用 `sandbox_permissions=require_escalated` + `justification` 重新申请 |
| `GateRequest` / `GateAnswer`（PROCEED / REJECT / STOP / ALWAYS / DELETE_CONTENTS） | `ExecApprovalRequest` 等 + `ReviewDecision` | `ApprovalRequest`（种类、命令、预览、理由、修订、选项）+ `ReviewDecision`（APPROVED / APPROVED_FOR_SESSION / APPROVED_ALWAYS / DENIED[带话] / DENIED_ALWAYS / ABORT） |
| 子 agent：写工具的才把闸摆到父会话 | 子线程共享父的审批 | 所有子 agent 的审批都摆到父会话，共用父会话的 `SessionPermissions` |
| web-fetch / web-search 只看开关 | 网络策略 + 主机审批 | `NetworkAccess`：网络开关 + `network_rule` + 本会话主机；可「以后放行 / 以后拦下」 |
| MCP 写工具、worktree、execute：开关开着才问 | MCP 审批 | 同 Codex MCP：never 且全盘可写 ⇒ 放，never 其他 ⇒ 拒，否则问（可以后都放行） |

## 4. 新设计

### 4.1 分层

```
sandbox 模块（与框架无关，可单独复用）
├── approval/ApprovalPolicy, ApprovalMode         审批策略（+ on-failure、granular 两个开关、wire 名）
├── approval/PatchSafety, SafetyCheck             写文件安全评估（safety.rs）
├── command/BashCommands                          bash -lc 拆词：严格（纯字面）/ 宽松（任意语法里的字面命令）——手写，不用 tree-sitter
├── command/CommandSafety                         已知安全 / 危险命令
├── execpolicy/{Policy, PolicyParser, Amend, …}   .rules 语言、求值、追加修订
├── execpolicy/ExecApprovals                      一条命令的审批要求（exec_policy.rs）
├── execpolicy/ExecPolicyManager                  多目录加载、坏文件跳过、修订落盘
└── orchestrator/SandboxOrchestrator（已有）       审批 → 沙盒 → 拒判 → 重跑

agent-core
├── approval/PermissionSettings, ApprovalPreset   审批 × 沙盒（+ 网络、可写根）与预设
├── approval/SessionPermissions                   一段会话（含子 agent）的权限状态：设置、规则、本会话不再问、以后都放行的工具
├── approval/ApprovalRequest, ApprovalChoice,     给界面的审批 API
│            ReviewDecision, Amendment
├── approval/ToolApprovals                        每次工具调用的判定（gate 过滤器调它）
├── approval/NetworkAccess, SecretFiles
├── sandbox/SandboxedCommandRunner                run-command 执行器：ExecApprovals → 编排器（审批摆到会话）
├── sandbox/CommandSandbox                        本机平台沙盒探测（与策略解耦）
└── runtime/AgentRuntime                          设置解析与持久化、规则按项目根加载
```

### 4.2 每类调用怎么判

| 调用 | 判定 | 要问时的请求 |
|---|---|---|
| 任何工具，参数里有 `.env` | never / granular 关沙盒审批 ⇒ 拒；否则问（本会话批过的同一调用不再问） | `SECRET` |
| 只读工具 | 放 | — |
| run-command | `ExecApprovals`（规则 + 启发式 × 策略）→ 编排器：沙盒里跑；拦下且策略许 ⇒ 问重跑。模型可带 `sandbox_permissions` / `justification` / `prefix_rule`；never 下申请出沙盒直接回绝 | `EXEC` / `EXEC_RETRY`（修订 = 前缀） |
| write-file / edit-file / replace-form / apply-patch / delete-path | `PatchSafety`：可写范围内 ⇒ 放；否则 never ⇒ 拒，否则问；untrusted ⇒ 都问；开着批量审阅时要问的写先攒着 | `PATCH`（本会话按文件记）/ `REVIEW` |
| web-fetch / web-search | 拉黑 ⇒ 拒；网络开着 / 主机放行 / 本会话放行过 ⇒ 放；never ⇒ 拒；否则问 | `NETWORK`（修订 = 主机） |
| 其他会改东西的工具（MCP、worktree、execute） | 以后都放行 / 本会话批过 ⇒ 放；never：全盘可写 ⇒ 放，否则拒；否则问。worktree-enter 只换根，直接放 | `TOOL`（修订 = 工具） |

未知命令在**没有平台沙盒**（macOS、bwrap 起不来）且策略受限时：已知安全 ⇒ 放，其余 ⇒ 问（never ⇒ 拒）——
照 Codex 对"Windows 没有沙盒后端"的处理，宁可多问，不让人以为命令关在沙盒里。

### 4.3 给界面的 API

```java
// agent-core io.ailink.agentforge.agent.approval
record ApprovalRequest(String id, Kind kind, String session, String toolUseId, String toolName, String title,
                       List<String> command, String cwd, Preview.Result preview, String reason, String retryReason,
                       String host, Amendment amendment, ObjectNode input, List<ApprovalChoice> choices)
enum Kind { EXEC, EXEC_RETRY, PATCH, NETWORK, TOOL, SECRET, REVIEW }
record ApprovalChoice(ReviewDecision.Kind decision, String key, String label)     // 键：y p a d b n
record ReviewDecision(Kind kind, String feedback)                                  // feedback 只给 DENIED / ABORT
enum ReviewDecision.Kind { APPROVED, APPROVED_FOR_SESSION, APPROVED_ALWAYS, DENIED, DENIED_ALWAYS, ABORT }
sealed interface Amendment { PrefixRule(List<String>), NetworkHost(String), Tool(String) }

// AgentSession
ApprovalRequest pendingApproval();
boolean resolveApproval(ReviewDecision d);          // 不在 choices 里的决定按 DENIED
ReviewDecision requestApproval(ApprovalRequest r);  // 工具线程上自己发起
SessionPermissions permissions();                   // settings() / setSettings(…) 即时生效
// SessionEvent
ApprovalRequested(ApprovalRequest) · ApprovalResolved(ApprovalRequest, ReviewDecision)
AutoApproval(tool, summary, allowed, why) · PolicyAmended(description) · Review(ReviewDecision, files)
// AgentRuntime
PermissionSettings permissionSettings(); setPermissionSettings(ps); applyPreset(p);
ExecPolicyManager execPolicy(Path root); List<String> reloadRules(); Path userRulesDir(); static Path projectRulesDir(root)
```

界面只需要：按 `kind` 选标题，显示 `title` / `command` / `preview`（`TEXT` 的别按 diff 上色）/ `reason` / `retryReason`，
把 `choices` 画成选项（`label` 已是中文、`key` 是建议快捷键），选中后 `resolveApproval(new ReviewDecision(choice.decision(), 可选的话))`。
`session` 与当前会话 id 不同 ⇒ 是子 agent 问的。

### 4.4 配置与文件

| 东西 | 位置 |
|---|---|
| 当前设置（审批、沙盒、网络、可写根、以后都放行的工具） | `state/agent.json`：`approval` / `sandbox` / `network` / `writable_roots` / `approved_tools` |
| 解析顺序 | builder 的整套设置 → prefs 打底，`$AGENTFORGE_APPROVAL` / `$AGENTFORGE_SANDBOX` 盖上，builder / 命令行单项再盖上 |
| 内建规则 | jar 里的 `io/ailink/agentforge/agent/approval/builtin.rules`；`<配置目录>/rules/builtin.rules` 存在（可为空）就盖掉它 |
| 用户规则 | `<配置目录>/rules/*.rules`；修订追加到 `rules/default.rules` |
| 项目规则 | `<根>/.agentforge/rules/*.rules`：只收紧（allow 规则不生效）；`.agentforge` 在沙盒里只读，模型改它要审批 |

命令行（`agentforge agent`）：`-s/--sandbox`、`-a/--ask-for-approval`、`--full-auto`、`--dangerously-bypass-approvals-and-sandbox`/`--yolo`、
`--preset`、`--network`/`--no-network`、`--add-dir`（可重复）。REPL：`/permissions [预设]`、`/approvals <策略>`、`/sandbox <档>`、
`/network on|off`、`/rules [reload]`；审批时 `y / a / p / d [话] / b / n [话]`。

## 5. 迁移说明

### 5.1 被替换掉的

- **审批开关**：`AgentSession.gate()/setGate`、`AgentRuntime.gateDefault/setGateDefault`、`Builder.gate(…)`、prefs 的 `gate`、`/gate` 全部删除。
  旧的"开关关着" ≈ 新的 on-request（默认）；"开着" ≈ untrusted。prefs 里残留的 `gate` 键不再读。
- **"以后都放行"规则**：`permission/{Permission, PermissionRule, PermissionRules}`、`SessionStore.permissions/putPermission/forgetPermissions`
  删除。会话库里残留的 `perm/<根>` 键不再读、也不迁移——旧规则是参数串通配（`cmd=git *`），语义上对不上前缀规则；
  需要的话照 `.rules` 语法手写到 `<配置目录>/rules/`。注意新修订是**用户级**的（跟 Codex 一样写进用户的 `default.rules`），
  不再按项目根隔开；项目目录 `.agentforge/rules/` 只能放收紧的规则（prompt / forbidden）。
- **`GateRequest` / `GateAnswer` / `GateApprover` / `SessionEvent.Gate*`** 换成 `ApprovalRequest` / `ReviewDecision` /
  `SessionEvent.Approval*`；`SessionStatus.AWAITING_GATE` 改名 `AWAITING_APPROVAL`。
- **`--no-sandbox`** 换成 `-s danger-full-access` 或 `--yolo`。

### 5.2 启发式的去向

- `rm` / `rmdir` / `unlink` / `shred` / `find -delete` / `git clean`：沙盒已经把删除限在可写根里；真正"沙盒也救不回来"的由
  `CommandSafety`（`rm -f` 一族，任何 shell 语法里）和内建规则（`git clean`、`git reset --hard`、强推、`git checkout -- .`）要求审批。
  不带 `-f` 的 `rm` 在工作根里不再问。
- `.git/` 内部：受保护名机制（与沙盒同一份策略）——写 `.git` 下的东西按越界写走审批，子进程里 `.git` 本来就只读。
- 删软链：链接本身在可写范围里 ⇒ 不问、只删链接；"连内容一起删"的选项去掉（要删目标就对真实路径调 delete-path，按它在哪儿判）。

### 5.3 保留的：密钥文件（`.env`）

Codex 没有"别把密钥读进上下文"的保护，它只有沙盒的 deny-read 条目。deny-read 不合适拿来做这件事：有 deny 条目时命令永远不能出沙盒
（`unsandboxed_execution_allowed = false`），没有沙盒后端的机器上所有命令都会被拒，而且没有"批这一次"的口子。所以保留 coxswain 的
做法：任何工具的参数里出现 `.env` / `.env.*`（模板后缀除外）就先问，理由是不可逆——密钥进了上下文就收不回来。它只看词、不懂 shell 语义，
是提醒不是保证；never 下直接拒（无人可问）。

## 6. 没有照搬的部分

| Codex 的东西 | 处理 | 原因 |
|---|---|---|
| untrusted 的「已知安全命令」白名单（上游 942af8447b 已退役） | **保留** | 没有它 untrusted 下连 `ls` 都要问；没有沙盒后端时也靠它免掉读命令的审批。git 依旧不算安全 |
| on-failure（上游 2cf2a6a844 已删） | **保留** | 用户点名要；语义照旧版：先跑后问。模型申请出沙盒时改成问（旧版是在处理器里直接拒） |
| tree-sitter-bash | 手写解析器 | 不引入原生依赖；严格解析器按 Codex 的拒绝清单逐条移植（测试搬过来了）；宽松解析器只用于发现危险命令 |
| Starlark 解释器 | 子集 | 规则文件只用到调用、字面量、列表、变量、`+`；坏文件单独跳过（上游整体退回空策略） |
| apply_patch 自动放行要求"有平台沙盒兜底" | 去掉这个条件 | 文件工具是进程内的，路径先经工作根限定与 realpath 复核；硬链接指到根外的风险记在案，未处理 |
| 网络代理（主机级 allow / deny、托管代理、MITM） | 进程内工具用同一套主机规则（`HostPolicy` + `network_rule`）；子进程只有开 / 断 | 子进程的 PROXIED 模式要 LD_PRELOAD 代理桥原生件，app-cli 目前不随带；另外代理里的"问用户"要能路由到具体会话，留待以后 |
| `with_additional_permissions` / `request_permissions` 工具 / 按命令临时放开某些路径 | 不做 | 需要逐命令改写沙盒策略与"本轮授权"状态；`require_escalated` 已覆盖主要场景 |
| Guardian 自动审查、strict auto review、hooks | 不做 | 与审批模型正交，属于另一块功能 |
| 项目受信（trust）决定是否加载项目 `.rules` | 改成「项目规则只收紧」：总是加载，但其中的 allow（前缀与网络）丢掉并警告 | 仓库带来的规则不可信——一条 `prefix_rule(["bash"], "allow")` 就能让所有命令绕过沙盒；prompt / forbidden 只会更严，可以放心收。放宽只能写进用户规则目录或经审批时的修订 |
| 每轮注入"当前沙盒 / 审批模式"的开发者消息 | 不做 | 会让提示缓存每次改设置都失效；改为在 run-command 的参数说明与沙盒拦截说明里告诉模型怎么申请 |
| PowerShell / Windows 的安全与危险命令表 | 不移植 | 只按 POSIX 判；Windows 后端本来就只编译不测 |
| MCP 工具按 annotations（destructive / open_world）分级 | 简化成 readOnly 与否 | AgentForge 的 MCP 目录只保留了 readOnly 提示 |

## 7. 测试

- sandbox：`BashCommandsTest`、`CommandSafetyTest`（is_safe_command / is_dangerous_command 的用例）、`PolicyParserTest`（execpolicy/tests/basic.rs、amend.rs）、
  `ExecApprovalsTest`（core/exec_policy_tests.rs）、`PatchSafetyTest`（core/safety_tests.rs）、`ApprovalRequirementTest`（+ on-failure、wire 名）。
- agent-core：`ToolApprovalsTest`（每类调用的判定、修订落盘、删除确认、网络规则）、`SandboxRunnerTest`（真 bwrap：on-request 回报拦截与显式升级、
  on-failure 重跑审批、只读预设、完全放开、没有后端时逐条审批、never 回绝升级与规则提示）、`AgentSessionTest` / `AgentRuntimeTest` / `TaskToolTest`
  里的审批流程（拒绝带话、停下、批量审阅、子 agent 摆到父会话、修订写进 `default.rules`、项目与内建规则加载）。
- app-cli：`AgentReplTest`（快捷键与答案解析、命令行开关组合）。
