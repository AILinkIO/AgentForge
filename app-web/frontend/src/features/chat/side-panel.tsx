import { Bot, CheckCircle2, Circle, CircleDot, Gauge, ListTodo } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cacheHit, fmtCost, fmtMs, fmtTokens, pct } from '@/lib/format'
import type { Snapshot } from '@/lib/types'
import { cn } from '@/lib/utils'
import { toolLook } from './tool-icons'

/** 右栏：上下文与用量、计划（todo）、子 agent。 */
export function SidePanel({ snap }: { snap: Snapshot }) {
  const u = snap.usage
  const ratio = u.contextRatio
  const hit = cacheHit(u.total)
  const runs = [...snap.subRuns].sort((a, b) => Number(b.running) - Number(a.running) || Number(a.background) - Number(b.background))
  const done = snap.todos.filter((t) => t.status === 'DONE').length
  return (
    <div className='flex flex-col gap-4'>
      <Card className='gap-3 py-4' data-testid='side-context'>
        <CardHeader className='px-4'>
          <CardTitle className='flex items-center gap-2 text-sm'><Gauge className='size-4 text-muted-foreground' />上下文</CardTitle>
        </CardHeader>
        <CardContent className='space-y-3 px-4'>
          <div>
            <div className='mb-1.5 flex items-baseline justify-between text-xs'>
              <span className='text-muted-foreground'>窗口占用</span>
              <span className='num font-medium'>{pct(ratio)}{u.contextLimit ? <span className='text-muted-foreground'> / {fmtTokens(u.contextLimit)}</span> : null}</span>
            </div>
            <div className='h-1.5 overflow-hidden rounded-full bg-muted'>
              <div className={cn('h-full rounded-full transition-all', (ratio ?? 0) > 0.85 ? 'bg-destructive' : (ratio ?? 0) > 0.6 ? 'bg-warning' : 'bg-primary')}
                style={{ width: `${Math.min(100, (ratio ?? 0) * 100)}%` }} />
            </div>
          </div>
          <dl className='grid grid-cols-2 gap-x-3 gap-y-2 text-xs'>
            <Stat label='输入' value={fmtTokens(u.total?.input)} />
            <Stat label='输出' value={fmtTokens(u.total?.output)} />
            <Stat label='缓存命中' value={pct(hit)} />
            <Stat label='花费' value={fmtCost(u.cost, u.costPartial)} />
          </dl>
          {snap.compactions > 0 && <div data-testid='compaction-count' className='text-[11px] text-muted-foreground'>已压缩 {snap.compactions} 次</div>}
          {snap.turnMs != null && !snap.session.busy && <div className='text-[11px] text-muted-foreground'>上一轮用时 {fmtMs(snap.turnMs)}</div>}
        </CardContent>
      </Card>

      <Card className='gap-3 py-4' data-testid='side-plan'>
        <CardHeader className='px-4'>
          <CardTitle className='flex items-center gap-2 text-sm'>
            <ListTodo className='size-4 text-muted-foreground' />计划
            {snap.todos.length > 0 && <span className='ms-auto text-xs font-normal text-muted-foreground'>{done}/{snap.todos.length}</span>}
          </CardTitle>
        </CardHeader>
        <CardContent className='px-4'>
          {snap.todos.length === 0 ? <div className='text-xs text-muted-foreground'>还没有计划</div> : (
            <ul className='space-y-1.5'>
              {snap.todos.map((t, i) => (
                <li key={i} className='flex items-start gap-2 text-xs' data-testid='todo-item' data-status={t.status}>
                  {t.status === 'DONE' ? <CheckCircle2 className='mt-px size-3.5 shrink-0 text-success' />
                    : t.status === 'DOING' ? <CircleDot className='mt-px size-3.5 shrink-0 text-info' />
                      : <Circle className='mt-px size-3.5 shrink-0 text-muted-foreground' />}
                  <span className={cn(t.status === 'DONE' && 'text-muted-foreground line-through', t.status === 'DOING' && 'font-medium')}>{t.text}</span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card className='gap-3 py-4' data-testid='side-subagents'>
        <CardHeader className='px-4'>
          <CardTitle className='flex items-center gap-2 text-sm'>
            <Bot className='size-4 text-muted-foreground' />子 agent
            {runs.some((r) => r.running) && <span className='ms-auto text-xs font-normal text-info'>{runs.filter((r) => r.running).length} 个在跑</span>}
          </CardTitle>
        </CardHeader>
        <CardContent className='px-4'>
          {runs.length === 0 ? <div className='text-xs text-muted-foreground'>没有派过子 agent</div> : (
            <ul className='space-y-2.5'>
              {runs.map((r) => {
                const last = r.steps[r.steps.length - 1]
                return (
                  <li key={r.id} className='text-xs'>
                    <div className='flex items-center gap-1.5'>
                      <span className={cn('size-1.5 shrink-0 rounded-full', r.running ? 'animate-pulse bg-info' : r.ok ? 'bg-success' : 'bg-destructive')} />
                      <span className='font-medium'>{r.who ?? '子 agent'}</span>
                      {r.background && <span className='rounded border px-1 text-[10px] text-muted-foreground'>后台</span>}
                      <span className='ms-auto text-muted-foreground'>{r.steps.length} 步{r.ms != null ? ` · ${fmtMs(r.ms)}` : ''}</span>
                    </div>
                    <div className='mt-0.5 line-clamp-2 ps-3 text-muted-foreground'>{r.desc}</div>
                    {r.running && last && <div className='mt-0.5 truncate ps-3 text-info'>{toolLook(last.name).verb} <span className='font-mono text-muted-foreground'>{last.args}</span></div>}
                  </li>
                )
              })}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className='text-muted-foreground'>{label}</dt>
      <dd className='num text-sm font-medium'>{value}</dd>
    </div>
  )
}
