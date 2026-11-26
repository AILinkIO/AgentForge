// 后端 JSON 的形状（见 app-web 的 Views.java）。Entry.meta 的键保持 agent-core 原样（kebab-case）。

export type SessionStatus = 'IDLE' | 'THINKING' | 'STREAMING' | 'AWAITING_APPROVAL' | 'AWAITING_QUESTION' | 'COMPACTING' | 'ERROR' | 'DONE'

export interface Usage { input: number; output: number; cacheRead: number; cacheWrite: number; reasoning: number }

export interface SessionSummary {
  id: string
  parentId?: string
  title?: string
  status: SessionStatus
  busy: boolean
  model?: string
  agent?: string
  project?: string
  root?: string
  conversation?: number
  usage?: Usage
  cost?: number
  pendingApproval: boolean
  pendingQuestion: boolean
  runningSubagents: number
}

export interface EntryMeta {
  'tool-name'?: string
  kind?: 'call' | 'result'
  'tool-use-id'?: string
  'args-preview'?: string
  args?: Record<string, unknown>
  'tool-error'?: boolean
  summary?: string
  thinking?: boolean
  'think-ms'?: number
  error?: boolean
  group?: string
  'sub-steps'?: number
  'sub-ms'?: number
  'sub-bg'?: boolean
  'sub-who'?: string
  'sub-model'?: string
  'sub-status'?: string
  'sub-why'?: string
  /** 压缩留下的两条：notice（分隔）/ summary（前情提要） */
  compaction?: 'notice' | 'summary'
  [k: string]: unknown
}

export interface Entry { role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM'; text: string; meta: EntryMeta }

export interface ApprovalChoice { decision: string; key: string; label: string }

export interface ApprovalRequest {
  id: string
  kind: 'EXEC' | 'EXEC_RETRY' | 'PATCH' | 'NETWORK' | 'TOOL' | 'SECRET' | 'REVIEW'
  session?: string
  toolUseId?: string
  toolName?: string
  title?: string
  command?: string[]
  cwd?: string
  preview?: { kind: 'DIFF' | 'TEXT'; text?: string }
  reason?: string
  retryReason?: string
  host?: string
  amendment?: string
  choices: ApprovalChoice[]
}

export interface Question { question: string; header?: string; options: { label: string; description?: string }[]; multiple: boolean }
export interface QuestionRequest { toolUseId?: string; questions: Question[] }

export interface SubStep { name: string; args?: string; error: boolean; summary?: string }
export interface SubRun {
  id: string
  desc: string
  status?: string
  running: boolean
  ok: boolean
  ms?: number
  startedAt: number
  background: boolean
  who?: string
  model?: string
  why?: string
  steps: SubStep[]
}

export interface Todo { status: 'PENDING' | 'DOING' | 'DONE'; text: string }

export interface UsageBlock {
  total?: Usage
  lastInput?: number
  lastOutput?: number
  cost?: number
  costPartial: boolean
  contextRatio?: number
  contextLimit?: number
}

export interface Snapshot {
  session: SessionSummary
  root: string
  thinking?: string
  thinkingLevels?: string[]
  entries: Entry[]
  streaming: { kind: string; text: string; epoch: number }
  approval?: ApprovalRequest
  question?: QuestionRequest
  usage: UsageBlock
  todos: Todo[]
  subRuns: SubRun[]
  children: SessionSummary[]
  queue: string[]
  draft?: string
  error?: string
  resultStatus?: string
  turnMs?: number
  compactions: number
  attachments: string[]
}

export interface Info {
  version: string
  root: string
  configDir: string
  model?: string
  host: string
  home: string
  modelProblem?: string
  sandboxStatus: string
  permissions: string
  notes: string[]
  storeWarning?: string
  subagents: boolean
  agents: { name: string; description: string; subagent: boolean }[]
}

export interface ModelInfo {
  ref: string
  provider: string
  id: string
  label?: string
  contextWindow?: number
  maxOutput?: number
  thinkingLevels?: string[]
  builtin: boolean
  reasons: boolean
  attachments: boolean
  providerEnabled: boolean
  source: 'models.dev' | 'config'
  providerLabel?: string
}

export interface ConfigModel { id: string; label?: string; context?: number; maxOutput?: number; thinking?: string[] }

export interface Credential {
  id: string
  label: string
  api: 'anthropic' | 'openai'
  baseUrl?: string
  source: 'catalog' | 'config'
  keySource?: 'env' | 'file'
  keyEnv?: string
  keyHint?: string
  envNames: string[]
  models: number
  catalogModels: number
  configModels?: ConfigModel[]
  apiKeyEnv?: string
}

export interface CredentialInput {
  source?: 'catalog' | 'config'
  provider?: string
  id?: string
  label?: string
  api?: string
  baseUrl?: string
  apiKeyEnv?: string
  key?: string
  models?: ConfigModel[]
}

export interface CatalogStatus {
  source: 'NONE' | 'CACHE' | 'BUNDLED'
  syncedAt?: number
  ageSeconds?: number
  stale: boolean
  providers: number
  models: number
  url: string
  sync: CatalogSyncStatus
}

export interface CatalogSyncStatus {
  running: boolean
  bytes: number
  url?: string
  startedAt?: number
  finishedAt?: number
  ok?: boolean
  error?: string
}

export interface FsSuggest { home: string; repos: { name: string; path: string; parent: string; used: boolean }[] }

export interface CatalogProvider {
  id: string
  label: string
  enabled: boolean
  hasKey: boolean
  models: number
  formatKnown: boolean
  needsEndpoint: boolean
  api?: string
  envNames: string[]
}

export interface ModelsResponse {
  current?: string
  models: ModelInfo[]
  providers: { id: string; label: string; enabled: boolean; hasKey: boolean }[]
  problem?: string
}

export interface Permissions {
  preset?: string
  approval: string
  sandbox: string
  network: boolean
  writableRoots: string[]
  describe: string
  sandboxStatus: string
  presets: { id: string; label: string; description: string; approval: string; sandbox: string }[]
}

export interface ToolInfo {
  name: string
  description: string
  readOnly: boolean
  sensitive: boolean
  enabled: boolean
  params: { name: string; type: string; required: boolean; description?: string }[]
}

export interface McpServer {
  name: string
  status: 'DISABLED' | 'CONNECTING' | 'CONNECTED' | 'FAILED' | 'NEEDS_AUTH'
  error?: string
  tools: string[]
  instructions?: string
  since?: string
  fromConfig: boolean
  stderrTail?: string
}

export interface Conversation {
  id: number
  title?: string
  created: number
  updated: number
  parent?: number
  messages: number
  project?: string
  root?: string
}

export interface Project {
  id: string
  name: string
  folders: string[]
  instructions?: string
  model?: string
  preset?: string
  created: number
  updated: number
  conversations: number
  missing: string[]
}

export interface ProjectInput { name: string; folders: string[]; instructions?: string; model?: string; preset?: string }

export interface FsListing {
  path: string
  parent?: string
  home: string
  git: boolean
  dirs: { name: string; path: string; git: boolean }[]
  truncated?: boolean
}

export interface SearchHit { conversation: number; seq: number; role: string; snippet: string; title?: string }

export interface ToolDetail {
  toolUseId: string
  name?: string
  input?: Record<string, unknown>
  text?: string
  error?: boolean
  images?: number
  diff?: string
}

export interface AgentEvent {
  type: string
  session?: string
  parent?: string
  [k: string]: unknown
}
