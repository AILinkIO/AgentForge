import { useQueries, useQuery } from '@tanstack/react-query'
import { Link } from 'wouter'
import { Bot, Loader2 } from 'lucide-react'
import { Header } from '@/components/layout/header'
import { Empty, Page } from '@/components/common/page'
import { StatusDot } from '@/components/common/status-dot'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { api, qk } from '@/lib/api'
import { fmtMs, STATUS_LABEL } from '@/lib/format'
import { toolLook } from '../chat/tool-icons'

/** 所有 agent 一览（TUI 的 C-c a）：每段主会话 + 它派出去的子 agent，正在做什么、在等你什么。 */
export function AgentsPage() {
  const { data: sessions = [], isLoading } = useQuery({ queryKey: qk.sessions, queryFn: api.sessions })
  const snaps = useQueries({ queries: sessions.map((s) => ({ queryKey: qk.snapshot(s.id), queryFn: () => api.snapshot(s.id) })) })

  return (
    <>
      <Header title='Agents' />
      <Page title='Agents' description='主会话和它们派出去的子 agent'>
        {isLoading ? <Loader2 className='size-5 animate-spin text-muted-foreground' /> : sessions.length === 0 ? (
          <Empty icon={<Bot className='size-5' />} title='没有在跑的 agent'>开一段会话，派活给它；它派出去的子 agent 也会列在这里。</Empty>
        ) : (
          <div className='grid gap-4 lg:grid-cols-2'>
            {sessions.map((s, i) => {
              const snap = snaps[i]?.data
              const runs = [...(snap?.subRuns ?? [])].sort((a, b) => Number(b.running) - Number(a.running) || Number(a.background) - Number(b.background))
              const calls = (snap?.entries ?? []).filter((e) => e.meta.kind === 'call' && e.meta.summary == null && !e.meta.group)
              const doing = s.busy ? calls[calls.length - 1] : undefined
              return (
                <Card key={s.id}>
                  <CardHeader>
                    <CardTitle className='flex items-center gap-2'>
                      <StatusDot status={s.status} attention={s.pendingApproval || s.pendingQuestion} />
                      <Link href={`/chat/${s.id}`} className='truncate hover:underline'>{s.title ?? '新会话'}</Link>
                      <Badge variant='outline' className='ms-auto shrink-0'>{s.agent ?? 'main'}</Badge>
                    </CardTitle>
                    <CardDescription className='flex items-center gap-2'>
                      <span>{s.pendingApproval ? '等你审批' : s.pendingQuestion ? '等你回答' : STATUS_LABEL[s.status]}</span>
                      {doing && <span className='truncate text-info'>· {toolLook(doing.meta['tool-name']).verb} <span className='font-mono text-muted-foreground'>{doing.meta['args-preview']}</span></span>}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    {runs.length === 0 ? <div className='text-xs text-muted-foreground'>没有派过子 agent</div> : (
                      <ul className='divide-y rounded-lg border'>
                        {runs.map((r) => {
                          const last = r.steps[r.steps.length - 1]
                          const elapsed = r.running ? Date.now() - r.startedAt : r.ms
                          return (
                            <li key={r.id} className='flex items-start gap-3 px-3 py-2.5 text-sm'>
                              <span className={`mt-1.5 size-2 shrink-0 rounded-full ${r.running ? 'animate-pulse bg-info' : r.ok ? 'bg-success' : 'bg-destructive'}`} />
                              <div className='min-w-0 flex-1'>
                                <div className='flex items-center gap-1.5'>
                                  <span className='font-medium'>{r.who ?? '子 agent'}</span>
                                  {r.background && <Badge variant='outline' className='h-5 text-[10px]'>后台</Badge>}
                                  {r.model && <span className='truncate font-mono text-[11px] text-muted-foreground'>{r.model}</span>}
                                </div>
                                <div className='line-clamp-2 text-xs text-muted-foreground'>{r.desc}</div>
                                {r.running && last && <div className='mt-0.5 truncate text-xs text-info'>{toolLook(last.name).verb} <span className='font-mono text-muted-foreground'>{last.args}</span></div>}
                                {!r.running && r.why && <div className='mt-0.5 text-xs text-destructive'>{r.why}</div>}
                              </div>
                              <div className='shrink-0 text-end text-[11px] text-muted-foreground'>
                                <div>{r.steps.length} 步</div>{elapsed != null && <div>{fmtMs(elapsed)}</div>}
                              </div>
                            </li>
                          )
                        })}
                      </ul>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}
      </Page>
    </>
  )
}
