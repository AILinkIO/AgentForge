import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Archive, Check, ChevronRight, CircleDot, Loader2, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { api } from '@/lib/api'
import { fmtMs } from '@/lib/format'
import type { Entry, SubRun } from '@/lib/types'
import { cn } from '@/lib/utils'
import { DiffView, Markdown } from './markdown'
import { toolLook } from './tool-icons'

export function UserMessage({ text }: { text: string }) {
  return (
    <div className='flex justify-end duration-300 animate-in fade-in-0 slide-in-from-bottom-1' data-testid='user-msg'>
      <div className='max-w-[85%] rounded-2xl rounded-br-md border border-primary/10 bg-primary-soft px-4 py-2.5 text-[0.9375rem] leading-relaxed whitespace-pre-wrap break-words'>
        {text}
      </div>
    </div>
  )
}

export function AssistantMessage({ text, streaming }: { text: string; streaming?: boolean }) {
  return (
    <div data-testid='assistant-msg' data-streaming={streaming ? 'true' : undefined}
      className={cn('text-[0.9375rem] duration-300 animate-in fade-in-0', streaming && '[&_.chat-md>:last-child]:stream-caret')}>
      <Markdown text={text} />
    </div>
  )
}

/** 思考过程：生成中默认展开、文字流光；结束后收起。 */
export function Thinking({ text, ms, streaming }: { text: string; ms?: number; streaming?: boolean }) {
  const [open, setOpen] = useState<boolean | null>(null)
  const shown = open ?? !!streaming
  return (
    <div data-testid='thinking'>
      <button type='button' onClick={() => setOpen(!shown)}
        className='-ms-2 inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-muted'>
        <ChevronRight className={cn('size-3.5 transition-transform', shown && 'rotate-90')} />
        <span className={cn(streaming && 'shimmer-text')}>{streaming ? '思考中…' : `想了 ${ms ? fmtMs(ms) : '一会儿'}`}</span>
      </button>
      {shown && <p className='ms-[7px] border-s ps-4 pt-1 pb-1 text-xs leading-relaxed whitespace-pre-wrap break-words text-muted-foreground'>{text}</p>}
    </div>
  )
}

/** 压缩留下的分隔线：上面的对话已经折成下面那份前情提要。 */
export function CompactionDivider() {
  return (
    <div data-testid='compaction-divider' className='flex items-center gap-3 py-1 text-xs text-muted-foreground'>
      <span className='h-px flex-1 bg-border' />
      <span className='flex items-center gap-1.5'><Archive className='size-3.5' />上下文已压缩，上面的对话不再发给模型</span>
      <span className='h-px flex-1 bg-border' />
    </div>
  )
}

/** 前情提要：默认收起（很长），点开看全文。 */
export function CompactionSummary({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  const body = text.replace(/^##\s*前情提要[^\n]*\n+/, '')
  return (
    <div data-testid='compaction-summary' className='rounded-xl border bg-muted/30'>
      <button type='button' onClick={() => setOpen(!open)} className='flex w-full items-center gap-2 px-3.5 py-2.5 text-start text-sm font-medium'>
        <ChevronRight className={cn('size-4 text-muted-foreground transition-transform', open && 'rotate-90')} />
        前情提要
        <span className='ms-auto text-xs font-normal text-muted-foreground'>{body.length} 字</span>
      </button>
      {open && <div className='border-t px-4 py-3 text-sm'><Markdown text={body} /></div>}
    </div>
  )
}

export function SystemNote({ text, error }: { text: string; error?: boolean }) {
  return (
    <div className={cn('flex items-start gap-2 rounded-lg border px-3 py-2 text-xs',
      error ? 'border-destructive/30 bg-destructive-soft text-destructive' : 'bg-muted/50 text-muted-foreground')}>
      {error ? <AlertTriangle className='mt-0.5 size-3.5 shrink-0' /> : <CircleDot className='mt-0.5 size-3.5 shrink-0' />}
      <span className='whitespace-pre-wrap break-words'>{text}</span>
    </div>
  )
}

/** 工具调用一行（Codex 式：图标 + 动词 + 参数摘要 + 结果摘要），点开看完整参数 / 输出 / diff。 */
export function ToolRow({ session, entry, result, running }: { session: string; entry: Entry; result?: Entry; running: boolean }) {
  const [open, setOpen] = useState(false)
  const m = entry.meta
  const name = m['tool-name']
  const id = m['tool-use-id']
  const { icon: Icon, verb } = toolLook(name)
  const done = m.summary != null || result != null
  const error = (m['tool-error'] ?? result?.meta['tool-error']) === true
  const summary = (m.summary ?? result?.meta.summary) as string | undefined
  // 有的工具参数没什么好看的（整份清单 markdown），行里只留结果摘要
  const preview = name === 'todo-write' ? undefined : m['args-preview']
  const cmd = name === 'run-command' ? String(m.args?.command ?? preview ?? '') : undefined

  return (
    <div className='group/tool' data-testid='tool-row' data-tool={name} data-state={!done && running ? 'running' : error ? 'error' : done ? 'done' : 'pending'}>
      <button type='button' onClick={() => setOpen(!open)}
        className='flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1 text-start text-[0.8125rem] hover:bg-muted/70 -ms-2'>
        <span className='flex size-4 shrink-0 items-center justify-center'>
          {!done && running ? <Loader2 className='size-3.5 animate-spin text-info' />
            : error ? <X className='size-3.5 text-destructive' />
              : done ? <Check className='size-3.5 text-success' /> : <Icon className='size-3.5 text-muted-foreground' />}
        </span>
        <span className='shrink-0 font-medium'>{verb}</span>
        {cmd ? <code className='min-w-0 truncate rounded bg-muted px-1.5 py-0.5 font-mono text-xs'>{cmd}</code>
          : <span className='min-w-0 truncate font-mono text-xs text-muted-foreground'>{name !== 'run-command' && name && verb !== name ? `${name} ` : ''}{preview}</span>}
        {summary && <span className={cn('ms-auto hidden max-w-[40%] shrink-0 truncate text-xs sm:inline', error ? 'text-destructive' : 'text-muted-foreground')}>{summary}</span>}
        <ChevronRight className={cn('size-3.5 shrink-0 text-muted-foreground opacity-0 transition group-hover/tool:opacity-100', !summary && 'ms-auto', open && 'rotate-90 opacity-100')} />
      </button>
      {open && id && <ToolDetailView session={session} toolUseId={id} args={m.args} />}
    </div>
  )
}

function ToolDetailView({ session, toolUseId, args }: { session: string; toolUseId: string; args?: Record<string, unknown> }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['tool', session, toolUseId],
    queryFn: () => api.toolDetail(session, toolUseId),
    retry: false,
  })
  return (
    <div className='ms-[7px] mt-1 mb-2 space-y-2 border-s ps-4'>
      {args && Object.keys(args).length > 0 && (
        <pre className='max-h-48 overflow-auto rounded-md border bg-muted/40 px-3 py-2 font-mono text-xs whitespace-pre-wrap break-all text-muted-foreground'>
          {JSON.stringify(args, null, 2)}
        </pre>
      )}
      {isLoading && <div className='text-xs text-muted-foreground'>载入中…</div>}
      {error && <div className='text-xs text-muted-foreground'>结果还没回来</div>}
      {data?.diff && <div className='max-h-96 overflow-auto rounded-md border'><DiffView text={data.diff} className='py-2' /></div>}
      {data?.text != null && (
        <pre className={cn('max-h-96 overflow-auto rounded-md border px-3 py-2 font-mono text-xs whitespace-pre-wrap break-all',
          data.error ? 'border-destructive/30 bg-destructive-soft text-destructive' : 'bg-muted/40')}>
          {data.text || '（空）'}
        </pre>
      )}
      {data?.images && <div className='text-xs text-muted-foreground'>另有 {data.images} 张图片交给了模型</div>}
    </div>
  )
}

/** 子 agent 那一格：派给谁、做什么、跑在哪个模型、走了几步、正在做什么；点开列出步骤。 */
export function SubAgentCard({ entry, run }: { entry: Entry; run?: SubRun }) {
  const [open, setOpen] = useState(false)
  const m = entry.meta
  const status = run?.status ?? m['sub-status']
  const running = run ? run.running : status == null
  const ok = run ? run.ok : status === 'completed' || status === 'return-direct' || status === 'done'
  const steps = run?.steps ?? []
  const last = running ? steps[steps.length - 1] : undefined
  const ms = run?.ms ?? m['sub-ms']
  const elapsed = running && run ? Date.now() - run.startedAt : ms
  return (
    <div data-testid='subagent-card' data-running={running ? 'true' : 'false'} className={cn('overflow-hidden rounded-xl border bg-card', running && 'border-info/40')}>
      <button type='button' onClick={() => setOpen(!open)} className='flex w-full items-start gap-3 px-3.5 py-3 text-start hover:bg-muted/40'>
        <span className={cn('mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-lg',
          running ? 'bg-info-soft text-info' : ok ? 'bg-success-soft text-success' : 'bg-destructive-soft text-destructive')}>
          {running ? <Loader2 className='size-4 animate-spin' /> : ok ? <Check className='size-4' /> : <X className='size-4' />}
        </span>
        <div className='min-w-0 flex-1 space-y-1'>
          <div className='flex flex-wrap items-center gap-1.5'>
            <span className='text-sm font-medium'>{run?.who ?? m['sub-who'] ?? '子 agent'}</span>
            {(run?.background ?? m['sub-bg']) && <Badge variant='outline' className='h-5 text-[10px]'>后台</Badge>}
            {(run?.model ?? m['sub-model']) && <span className='truncate font-mono text-[11px] text-muted-foreground'>{run?.model ?? m['sub-model']}</span>}
          </div>
          <div className='line-clamp-2 text-xs text-muted-foreground'>{run?.desc || m['args-preview']}</div>
          {last && (
            <div className='flex items-center gap-1.5 truncate text-xs text-info'>
              <span className='shimmer-text'>{toolLook(last.name).verb}</span>
              <span className='truncate font-mono text-muted-foreground'>{last.args}</span>
            </div>
          )}
          {!running && (run?.why ?? m['sub-why']) && <div className='text-xs text-destructive'>{run?.why ?? m['sub-why']}</div>}
        </div>
        <div className='shrink-0 text-end text-[11px] leading-5 text-muted-foreground'>
          <div>{steps.length || m['sub-steps'] || 0} 步</div>
          {elapsed != null && <div>{fmtMs(elapsed as number)}</div>}
        </div>
      </button>
      {open && steps.length > 0 && (
        <div className='space-y-0.5 border-t bg-muted/20 px-3.5 py-2'>
          {steps.map((s, i) => {
            const { icon: Icon, verb } = toolLook(s.name)
            const pending = s.summary == null && running && i === steps.length - 1
            return (
              <div key={i} className='flex min-w-0 items-center gap-2 py-0.5 text-xs'>
                {pending ? <Loader2 className='size-3 shrink-0 animate-spin text-info' />
                  : s.error ? <X className='size-3 shrink-0 text-destructive' /> : <Icon className='size-3 shrink-0 text-muted-foreground' />}
                <span className='shrink-0 font-medium'>{verb}</span>
                <span className='min-w-0 truncate font-mono text-muted-foreground'>{s.args}</span>
                {s.summary && <span className={cn('ms-auto max-w-[40%] shrink-0 truncate', s.error ? 'text-destructive' : 'text-muted-foreground')}>{s.summary}</span>}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
