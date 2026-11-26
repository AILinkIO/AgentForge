import type {
  CatalogProvider, CatalogStatus, FsSuggest, Conversation, Credential, CredentialInput, FsListing, Info, Project, ProjectInput, McpServer, ModelsResponse, Permissions, SearchHit, SessionSummary, Snapshot, ToolDetail, ToolInfo,
} from './types'

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') headers['X-AgentForge'] = '1'          // 后端的 CSRF 门槛
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  let data: unknown = undefined
  try { data = text ? JSON.parse(text) : undefined } catch { /* 非 JSON 错误页 */ }
  if (!res.ok) {
    const msg = (data as { error?: string } | undefined)?.error ?? (text || res.statusText)
    throw new ApiError(res.status, msg)
  }
  return data as T
}

const get = <T,>(p: string) => request<T>('GET', p)
const post = <T,>(p: string, b?: unknown) => request<T>('POST', p, b ?? {})
const put = <T,>(p: string, b?: unknown) => request<T>('PUT', p, b ?? {})
const patch = <T,>(p: string, b?: unknown) => request<T>('PATCH', p, b ?? {})
const del = <T,>(p: string) => request<T>('DELETE', p)

export const api = {
  info: () => get<Info>('/info'),
  sessions: () => get<SessionSummary[]>('/sessions'),
  createSession: (opts: { agent?: string; project?: string; folder?: string } = {}) => post<SessionSummary>('/sessions', opts),
  snapshot: (id: string) => get<Snapshot>(`/sessions/${id}`),
  closeSession: (id: string) => del<void>(`/sessions/${id}`),
  send: (id: string, text: string) => post<{ result: 'SENT' | 'QUEUED' | 'IGNORED' }>(`/sessions/${id}/messages`, { text }),
  interrupt: (id: string) => post(`/sessions/${id}/interrupt`),
  compact: (id: string) => post(`/sessions/${id}/compact`),
  approve: (id: string, requestId: string, decision: string, feedback?: string) =>
    post<{ ok: boolean }>(`/sessions/${id}/approval`, { id: requestId, decision, feedback }),
  answer: (id: string, answers: string[][] | null) =>
    post<{ ok: boolean }>(`/sessions/${id}/answer`, answers ? { answers } : { dismissed: true }),
  settings: (id: string, s: { model?: string; thinking?: string; agent?: string; title?: string }) => patch<Snapshot>(`/sessions/${id}`, s),
  attach: (id: string, name: string, mediaType: string, data: string) =>
    post<{ count: number; attachments: string[] }>(`/sessions/${id}/attachments`, { name, mediaType, data }),
  clearAttachments: (id: string) => del<{ count: number }>(`/sessions/${id}/attachments`),
  toolDetail: (id: string, toolUseId: string) => get<ToolDetail>(`/sessions/${id}/tools/${encodeURIComponent(toolUseId)}`),
  conversations: (limit = 200, project?: string) =>
    get<Conversation[]>(`/conversations?limit=${limit}${project ? `&project=${encodeURIComponent(project)}` : ''}`),
  projects: () => get<Project[]>('/projects'),
  createProject: (p: ProjectInput) => post<Project>('/projects', p),
  updateProject: (id: string, p: ProjectInput) => put<Project>(`/projects/${id}`, p),
  deleteProject: (id: string) => del<void>(`/projects/${id}`),
  fs: (path?: string, hidden = false) =>
    get<FsListing>(`/fs?hidden=${hidden}${path ? `&path=${encodeURIComponent(path)}` : ''}`),
  mkdir: (parent: string, name: string) => post<FsListing>('/fs/mkdir', { parent, name }),
  search: (q: string) => get<SearchHit[]>(`/conversations/search?q=${encodeURIComponent(q)}`),
  openConversation: (conv: number) => post<SessionSummary>(`/conversations/${conv}/open`),
  deleteConversation: (conv: number) => del<void>(`/conversations/${conv}`),
  models: () => get<ModelsResponse>('/models'),
  selectModel: (ref: string) => put<ModelsResponse>('/models/default', { ref }),
  setKey: (provider: string, key: string) => put<ModelsResponse>(`/providers/${provider}/key`, { key }),
  removeKey: (provider: string) => del<ModelsResponse>(`/providers/${provider}/key`),
  credentials: () => get<Credential[]>('/credentials'),
  addCredential: (c: CredentialInput) => post<Credential[]>('/credentials', c),
  updateCredential: (id: string, c: CredentialInput) => put<Credential[]>(`/credentials/${encodeURIComponent(id)}`, c),
  removeCredential: (id: string) => del<Credential[]>(`/credentials/${encodeURIComponent(id)}`),
  catalog: () => get<CatalogStatus>('/catalog'),
  catalogProviders: () => get<CatalogProvider[]>('/catalog/providers'),
  syncCatalog: (url?: string) => post<CatalogStatus>('/catalog/sync', url ? { url } : {}),
  fsSuggest: () => get<FsSuggest>('/fs/suggest'),
  importCatalog: async (file: File) => {
    const res = await fetch('/api/catalog/import', {
      method: 'POST', headers: { 'X-AgentForge': '1', 'Content-Type': 'application/json' }, body: await file.text(),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new ApiError(res.status, data.error ?? res.statusText)
    return data as CatalogStatus
  },
  permissions: () => get<Permissions>('/permissions'),
  setPreset: (preset: string) => put<Permissions>('/permissions', { preset }),
  tools: (session?: string) => get<ToolInfo[]>(`/tools${session ? `?session=${session}` : ''}`),
  mcp: () => get<McpServer[]>('/mcp'),
  mcpReconnect: (name: string) => post<McpServer[]>(`/mcp/${encodeURIComponent(name)}/reconnect`),
}

export const qk = {
  info: ['info'] as const,
  sessions: ['sessions'] as const,
  snapshot: (id: string) => ['snapshot', id] as const,
  conversations: ['conversations'] as const,
  projects: ['projects'] as const,
  credentials: ['credentials'] as const,
  catalog: ['catalog'] as const,
  models: ['models'] as const,
  permissions: ['permissions'] as const,
  tools: ['tools'] as const,
  mcp: ['mcp'] as const,
}
