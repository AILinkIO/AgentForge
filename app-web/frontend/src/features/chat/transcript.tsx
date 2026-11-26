import { type ReactNode, useMemo, useState } from 'react'
import { ChevronRight, Loader2 } from 'lucide-react'
import type { Entry, Snapshot } from '@/lib/types'
import { cn } from '@/lib/utils'
import {
  AssistantMessage, CompactionDivider, CompactionSummary, SubAgentCard, SystemNote, Thinking, ToolRow, UserMessage,
} from './entries'
import { toolLook } from './tool-icons'

type ToolItem = { key: number; call?: Entry; result?: Entry; note?: Entry }

/** 一串工具调用的一句话摘要（Codex 的「Explored · Ran」）：按动词数一数，「运行了 2 条命令，读取了 3 个文件」。 */
function summarize(items: ToolItem[]): string {
  const counts = new Map<string, number>()
  for (const it of items) {
    if (!it.call) continue
    const verb = toolLook(it.call.meta['tool-name']).verb
    counts.set(verb, (counts.get(verb) ?? 0) + 1)
  }
  const unit: Record<string, string> = { 运行: '条命令', 读取: '个文件', 修改: '个文件', 写入: '个文件', 删除: '处', 搜索: '次', 找文件: '次', 列目录: '个目录' }
  return [...counts.entries()].map(([v, n]) => unit[v] ? `${v} ${n} ${unit[v]}` : n > 1 ? `${v} ×${n}` : v).join('，')
}

/**
 * 一组连着的工具调用：两条及以内直接平铺；更多时收成一个可展开的块（还在跑 / 有失败时默认展开）。
 */
function ToolGroup({ session, items, running }: { session: string; items: ToolItem[]; running: boolean }) {
  const calls = items.filter((i) => i.call)
  const pending = running && calls.some((i) => i.call!.meta.summary == null && !i.result)
  const failed = calls.filter((i) => (i.call!.meta['tool-error'] ?? i.result?.meta['tool-error']) === true).length
  const [open, setOpen] = useState<boolean | null>(null)
  const rows = items.map((it) => it.call
    ? <ToolRow key={it.key} session={session} entry={it.call} result={it.result} running={running} />
    : <SystemNote key={it.key} text={it.note!.text} error={it.note!.meta['tool-error']} />)
  if (items.length <= 2) return <div>{rows}</div>
  const shown = open ?? (pending || failed > 0)
  return (
    <div data-testid='tool-group' className='rounded-xl border bg-muted/20'>
      <button type='button' onClick={() => setOpen(!shown)}
        className='flex w-full items-center gap-2 px-3 py-2 text-start text-[0.8125rem] text-muted-foreground hover:text-foreground'>
        {pending ? <Loader2 className='size-3.5 animate-spin text-info' /> : <ChevronRight className={cn('size-3.5 transition-transform', shown && 'rotate-90')} />}
        <span className={cn('font-medium', pending && 'shimmer-text')}>{pending ? '正在干活' : '已完成'}</span>
        <span className='truncate'>{summarize(items)}</span>
        {failed > 0 && <span className='ms-auto shrink-0 rounded bg-destructive-soft px-1.5 text-[11px] text-destructive'>{failed} 个失败</span>}
      </button>
      {shown && <div className='border-t px-3 py-1.5 ps-5'>{rows}</div>}
    </div>
  )
}

/** 把记录区条目排成界面：调用与结果按 tool-use-id 配对（结果不单独成行），连着的调用收成一组，task 调用画成子 agent 卡。 */
export function Transcript({ snap, live }: { snap: Snapshot; live?: { kind: string; text: string } }) {
  const id = snap.session.id
  const busy = snap.session.busy
  const items = useMemo(() => {
    const results = new Map<string, Entry>()
    for (const e of snap.entries) if (e.role === 'TOOL' && e.meta.kind === 'result' && e.meta['tool-use-id']) results.set(e.meta['tool-use-id'], e)
    const calls = new Set<string>()
    for (const e of snap.entries) if (e.meta.kind === 'call' && e.meta['tool-use-id']) calls.add(e.meta['tool-use-id'])
    const runs = new Map(snap.subRuns.map((r) => [r.id, r]))
    const out: ReactNode[] = []
    let tools: ToolItem[] = []
    const flush = (k: number) => {
      if (tools.length) out.push(<ToolGroup key={`t${k}`} session={id} items={tools} running={busy} />)
      tools = []
    }
    snap.entries.forEach((e, i) => {
      const m = e.meta
      if (e.role === 'TOOL' && m.kind === 'call') {
        if (m.group) {
          flush(i)
          out.push(<SubAgentCard key={i} entry={e} run={runs.get(m.group)} />)
          return
        }
        tools.push({ key: i, call: e, result: m['tool-use-id'] ? results.get(m['tool-use-id']) : undefined })
        return
      }
      if (e.role === 'TOOL' && m.kind === 'result') {
        if (m['tool-use-id'] && calls.has(m['tool-use-id'])) return
        tools.push({ key: i, note: e })
        return
      }
      flush(i)
      if (m.compaction === 'notice') out.push(<CompactionDivider key={i} />)
      else if (m.compaction === 'summary') out.push(<CompactionSummary key={i} text={e.text} />)
      else if (e.role === 'USER') out.push(<UserMessage key={i} text={e.text} />)
      else if (e.role === 'ASSISTANT' && m.thinking) out.push(<Thinking key={i} text={e.text} ms={m['think-ms']} />)
      else if (e.role === 'ASSISTANT') out.push(<AssistantMessage key={i} text={e.text} />)
      else out.push(<SystemNote key={i} text={e.text} error={m.error} />)
    })
    flush(snap.entries.length)
    return out
  }, [snap, id, busy])

  return (
    <div className='flex flex-col gap-5'>
      {items}
      {busy && live?.text && (live.kind === 'thinking'
        ? <Thinking text={live.text} streaming />
        : <AssistantMessage text={live.text} streaming />)}
    </div>
  )
}
