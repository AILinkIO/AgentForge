import type { Usage } from './types'

export function fmtTokens(n?: number): string {
  if (n == null) return '—'
  if (n < 1000) return String(n)
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`
  return `${(n / 1_000_000).toFixed(2)}M`
}

export function fmtCost(c?: number, partial?: boolean): string {
  if (c == null) return '—'
  const s = c < 0.01 ? `$${c.toFixed(4)}` : `$${c.toFixed(2)}`
  return partial ? `≥${s}` : s
}

export function fmtMs(ms?: number): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)}s`
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

/** 命中率按 Anthropic 口径：cacheRead / (input + cacheRead + cacheWrite)。 */
export function cacheHit(u?: Usage): number | undefined {
  if (!u) return undefined
  const all = u.input + u.cacheRead + u.cacheWrite
  return all > 0 ? u.cacheRead / all : undefined
}

export function pct(r?: number): string {
  return r == null ? '—' : `${Math.round(r * 100)}%`
}

export function fmtTime(ms: number): string {
  const d = new Date(ms)
  const now = new Date()
  const same = d.toDateString() === now.toDateString()
  const hm = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (same) return hm
  const y = d.getFullYear() === now.getFullYear() ? '' : `${d.getFullYear()}-`
  return `${y}${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`
}

export function relTime(ms: number): string {
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 30 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return fmtTime(ms)
}

export const STATUS_LABEL: Record<string, string> = {
  IDLE: '空闲',
  THINKING: '思考中',
  STREAMING: '输出中',
  AWAITING_APPROVAL: '等你审批',
  AWAITING_QUESTION: '等你回答',
  COMPACTING: '压缩中',
  ERROR: '出错',
  DONE: '完成',
}
