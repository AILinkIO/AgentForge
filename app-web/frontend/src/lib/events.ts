import { useEffect, useSyncExternalStore } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { qk } from './api'
import type { AgentEvent, Snapshot } from './types'

/**
 * 实时层：一条 EventSource 连 /api/events。
 *  - delta：拼进 live（按 epoch + 结尾偏移判断接不接得上；接不上就重取快照）。
 *  - 其他：防抖重取那段会话的快照；sessions 事件重取侧栏。
 */
type Live = { epoch: number; kind: string; text: string }

const live = new Map<string, Live>()
const listeners = new Set<() => void>()
let version = 0
let connected = false

function bump() {
  version++
  for (const l of listeners) l()
}

export function subscribeLive(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** 某段会话正在流出来的那段字（快照里的 + 之后到的增量）。 */
export function useLive(session: string | undefined): Live | undefined {
  useSyncExternalStore(subscribeLive, () => version)
  return session ? live.get(session) : undefined
}

export function useConnected(): boolean {
  useSyncExternalStore(subscribeLive, () => version)
  return connected
}

/** 快照到手：以它为准重置 live。 */
export function seedLive(snap: Snapshot) {
  const id = snap.session.id
  const s = snap.streaming
  const cur = live.get(id)
  if (!s || !snap.session.busy) {
    if (cur) { live.delete(id); bump() }
    return
  }
  // 快照可能比手里的旧（请求在飞时又到了增量）：同一 epoch 下只接更长的
  if (cur && cur.epoch === s.epoch && cur.text.length >= s.text.length) return
  live.set(id, { epoch: s.epoch, kind: s.kind, text: s.text })
  bump()
}

const pending = new Map<string, ReturnType<typeof setTimeout>>()

function refetchSoon(qc: QueryClient, key: readonly unknown[], ms = 60) {
  const k = JSON.stringify(key)
  if (pending.has(k)) return
  pending.set(k, setTimeout(() => {
    pending.delete(k)
    qc.invalidateQueries({ queryKey: key })
  }, ms))
}

type Handler = (e: AgentEvent) => void
const eventHandlers = new Set<Handler>()

/** 页面想对个别事件做点额外的事（提示条等）。 */
export function onAgentEvent(h: Handler) {
  eventHandlers.add(h)
  return () => { eventHandlers.delete(h) }
}

export function useAgentEvents(qc: QueryClient) {
  useEffect(() => {
    let es: EventSource | null = null
    let retry: ReturnType<typeof setTimeout> | undefined
    let closed = false

    const open = () => {
      es = new EventSource('/api/events')
      es.addEventListener('hello', () => {
        connected = true
        bump()
        // 断线期间漏掉的都补回来
        qc.invalidateQueries()
      })
      es.addEventListener('agent', (msg) => {
        let e: AgentEvent
        try { e = JSON.parse((msg as MessageEvent).data) } catch { return }
        handle(qc, e)
        for (const h of eventHandlers) h(e)
      })
      es.onerror = () => {
        connected = false
        bump()
        es?.close()
        if (!closed) retry = setTimeout(open, 1500)
      }
    }
    open()
    return () => {
      closed = true
      clearTimeout(retry)
      es?.close()
    }
  }, [qc])
}

function handle(qc: QueryClient, e: AgentEvent) {
  if (e.type === 'sessions') {
    refetchSoon(qc, qk.sessions, 100)
    refetchSoon(qc, qk.conversations, 300)
    refetchSoon(qc, qk.projects, 300)
    return
  }
  if (e.type === 'catalog-sync') {
    // 进度直接写进缓存（不必重取）；跑完了再把目录、模型、凭证都刷一遍
    qc.setQueryData(qk.catalog, (old: unknown) => old ? { ...(old as object), sync: e.sync } : old)
    const s = e.sync as { running: boolean }
    if (!s.running) for (const k of [qk.catalog, qk.models, qk.credentials]) refetchSoon(qc, k, 50)
    return
  }
  if (e.type === 'models') {
    for (const k of [qk.models, qk.credentials, qk.catalog, qk.info]) refetchSoon(qc, k, 50)
    return
  }
  if (e.type === 'projects') {
    refetchSoon(qc, qk.projects, 50)
    refetchSoon(qc, qk.sessions, 100)
    return
  }
  const id = e.session
  if (!id) return
  if (e.type === 'delta') {
    const epoch = e.epoch as number
    const at = e.at as number
    const text = e.text as string
    const cur = live.get(id)
    if (cur && cur.epoch === epoch) {
      if (at - text.length === cur.text.length) {
        live.set(id, { epoch, kind: e.kind as string, text: cur.text + text })
        bump()
      } else if (at > cur.text.length) {
        refetchSoon(qc, qk.snapshot(id), 0)
      }
      return
    }
    if (!cur || epoch > cur.epoch) {
      // 新的一段：前一段落进了记录（快照里才有），开头接得上就先显示
      if (at === text.length) {
        live.set(id, { epoch, kind: e.kind as string, text })
        bump()
      }
      refetchSoon(qc, qk.snapshot(id), 30)
    }
    return
  }
  refetchSoon(qc, qk.snapshot(id))
  if (e.parent) refetchSoon(qc, qk.snapshot(e.parent))
  if (e.type === 'status' || e.type === 'usage' || e.type === 'approval' || e.type === 'question' || e.type.startsWith('sub-'))
    refetchSoon(qc, qk.sessions, 150)
}
