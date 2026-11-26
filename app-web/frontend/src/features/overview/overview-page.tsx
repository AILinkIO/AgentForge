import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation } from 'wouter'
import { Activity, ArrowRight, Bot, Coins, DatabaseZap, FolderOpen, FolderPlus, MessageSquarePlus, MessagesSquare, ShieldCheck, SquarePen, TriangleAlert } from 'lucide-react'
import { openProjectDialog } from '@/features/projects/project-dialog'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Page } from '@/components/common/page'
import { StatusDot } from '@/components/common/status-dot'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { api, qk } from '@/lib/api'
import { cacheHit, fmtCost, fmtTokens, pct, relTime, STATUS_LABEL } from '@/lib/format'
import type { Usage } from '@/lib/types'

/** 概览（shadcnuikit dashboard 的版式：标题行 + 四张统计卡 + 两栏卡片 + 表格）。 */
export function OverviewPage() {
  const qc = useQueryClient()
  const [, navigate] = useLocation()
  const { data: info } = useQuery({ queryKey: qk.info, queryFn: api.info })
  const { data: sessions = [] } = useQuery({ queryKey: qk.sessions, queryFn: api.sessions })
  const { data: convs = [] } = useQuery({ queryKey: qk.conversations, queryFn: () => api.conversations(200) })
  const { data: perms } = useQuery({ queryKey: qk.permissions, queryFn: api.permissions })
  const { data: mcp = [] } = useQuery({ queryKey: qk.mcp, queryFn: api.mcp })
  const { data: projects = [] } = useQuery({ queryKey: qk.projects, queryFn: api.projects })
  const pname = (id?: string) => projects.find((p) => p.id === id)?.name

  const open = useMutation({
    mutationFn: (conv: number) => api.openConversation(conv),
    onSuccess: (s) => { qc.invalidateQueries({ queryKey: qk.sessions }); navigate(`/chat/${s.id}`) },
    onError: (e: Error) => toast.error(e.message),
  })

  const total: Usage = sessions.reduce<Usage>((a, s) => s.usage ? {
    input: a.input + s.usage.input, output: a.output + s.usage.output, cacheRead: a.cacheRead + s.usage.cacheRead,
    cacheWrite: a.cacheWrite + s.usage.cacheWrite, reasoning: a.reasoning + s.usage.reasoning,
  } : a, { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0 })
  const cost = sessions.reduce<number | undefined>((a, s) => s.cost == null ? a : (a ?? 0) + s.cost, undefined)
  const busy = sessions.filter((s) => s.busy).length
  const subs = sessions.reduce((n, s) => n + s.runningSubagents, 0)
  const waiting = sessions.filter((s) => s.pendingApproval || s.pendingQuestion)
  const mcpUp = mcp.filter((m) => m.status === 'CONNECTED').length

  const stats = [
    { label: '开着的会话', value: String(sessions.length), sub: `${busy ? `${busy} 个正在干活` : '都空闲'} · ${projects.length} 个项目`, icon: MessagesSquare, tone: 'bg-primary-soft text-primary' },
    { label: '在跑的子 agent', value: String(subs), sub: subs ? '到 Agents 页看详情' : '没有', icon: Bot, tone: 'bg-info-soft text-info' },
    { label: '本次用量（token）', value: fmtTokens(total.input + total.output + total.cacheRead + total.cacheWrite), sub: `缓存命中 ${pct(cacheHit(total))}`, icon: DatabaseZap, tone: 'bg-success-soft text-success' },
    { label: '本次花费', value: fmtCost(cost), sub: '按 models.dev 价目估算', icon: Coins, tone: 'bg-warning-soft text-warning' },
  ]
  const tok = (u?: Usage) => u ? u.input + u.output + u.cacheRead + u.cacheWrite : 0
  const usageRows = sessions.filter((s) => tok(s.usage) > 0).sort((a, b) => tok(b.usage) - tok(a.usage)).slice(0, 6)
  const maxTok = Math.max(1, ...usageRows.map((s) => tok(s.usage)))
  const projConvs = (id: string) => convs.filter((c) => c.project === id && !c.parent).length

  return (
    <>
      <Header title='概览' />
      <Page title='概览' description={info ? <>{info.host} 上的 AgentForge · {projects.length} 个项目 · 模型 <code className='rounded bg-muted px-1.5 py-0.5 font-mono text-xs'>{info.model ?? '没选'}</code></> : '…'}
        actions={<Button onClick={() => navigate('/new')}><MessageSquarePlus className='size-4' />新聊天</Button>}>

        {(info?.notes.length || info?.modelProblem || info?.storeWarning) ? (
          <Card className='gap-2 border-warning/40 bg-warning-soft py-3'>
            <CardContent className='space-y-1 px-4 text-sm text-warning'>
              {info?.modelProblem && <div className='flex gap-2'><TriangleAlert className='mt-0.5 size-4 shrink-0' />{info.modelProblem}</div>}
              {info?.storeWarning && <div className='flex gap-2'><TriangleAlert className='mt-0.5 size-4 shrink-0' />{info.storeWarning}</div>}
              {info?.notes.map((n, i) => <div key={i} className='flex gap-2 text-subtle-foreground'><Activity className='mt-0.5 size-4 shrink-0' />{n}</div>)}
            </CardContent>
          </Card>
        ) : null}

        <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
          {stats.map((s) => (
            <Card key={s.label} className='gap-2 shadow-xs'>
              <CardHeader>
                <CardDescription>{s.label}</CardDescription>
                <CardAction><span className={`flex size-8 items-center justify-center rounded-lg ${s.tone}`}><s.icon className='size-4' /></span></CardAction>
                <CardTitle className='num text-[1.75rem] font-semibold tabular-nums'>{s.value}</CardTitle>
              </CardHeader>
              <CardContent className='text-xs text-muted-foreground'>{s.sub}</CardContent>
            </Card>
          ))}
        </div>

        <div className='grid gap-4 lg:grid-cols-3'>
          <Card className='lg:col-span-2'>
            <CardHeader>
              <CardTitle>开着的会话</CardTitle>
              <CardDescription>{waiting.length ? `${waiting.length} 个在等你` : '实时状态'}</CardDescription>
            </CardHeader>
            <CardContent>
              {sessions.length === 0 ? <div className='py-6 text-center text-sm text-muted-foreground'>还没有会话</div> : (
                <Table>
                  <TableHeader><TableRow><TableHead>会话</TableHead><TableHead className='hidden lg:table-cell'>项目</TableHead><TableHead>状态</TableHead><TableHead className='hidden md:table-cell'>模型</TableHead><TableHead className='text-end'>token</TableHead></TableRow></TableHeader>
                  <TableBody>
                    {sessions.map((s) => (
                      <TableRow key={s.id} className='cursor-pointer' onClick={() => navigate(`/chat/${s.id}`)}>
                        <TableCell className='max-w-64 truncate font-medium'>{s.title ?? '新会话'}</TableCell>
                        <TableCell className='hidden max-w-32 truncate text-muted-foreground lg:table-cell'>{pname(s.project) ?? '—'}</TableCell>
                        <TableCell>
                          <span className='flex items-center gap-2'><StatusDot status={s.status} attention={s.pendingApproval || s.pendingQuestion} />
                            {s.pendingApproval ? '等你审批' : s.pendingQuestion ? '等你回答' : STATUS_LABEL[s.status]}</span>
                        </TableCell>
                        <TableCell className='hidden max-w-48 truncate font-mono text-xs text-muted-foreground md:table-cell'>{s.model}</TableCell>
                        <TableCell className='num text-end'>{fmtTokens(s.usage ? s.usage.input + s.usage.output + s.usage.cacheRead + s.usage.cacheWrite : 0)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>运行环境</CardTitle>
              <CardDescription>权限、沙盒、模型、MCP</CardDescription>
              <CardAction><Button variant='ghost' size='sm' asChild><Link href='/permissions'>调整<ArrowRight className='size-3.5' /></Link></Button></CardAction>
            </CardHeader>
            <CardContent className='space-y-3 text-sm'>
              <Row label='权限预设'><Badge variant='secondary'><ShieldCheck className='size-3' />{perms?.presets.find((p) => p.id === perms.preset)?.label ?? '自定义'}</Badge></Row>
              <Row label='审批'><code className='font-mono text-xs'>{perms?.approval}</code></Row>
              <Row label='沙盒'><code className='font-mono text-xs'>{perms?.sandbox}</code></Row>
              <Row label='网络'>{perms?.network ? <Badge variant='warning'>可出网</Badge> : <Badge variant='secondary'>断网</Badge>}</Row>
              <Row label='模型'><span className='truncate font-mono text-xs'>{info?.model ?? '—'}</span></Row>
              <Row label='MCP'>{mcp.length ? `${mcpUp}/${mcp.length} 已连接` : '未配置'}</Row>
              <p className='border-t pt-3 text-xs text-muted-foreground'>{perms?.sandboxStatus}</p>
            </CardContent>
          </Card>
        </div>

        <div className='grid gap-4 lg:grid-cols-3'>
          <Card className='lg:col-span-2'>
            <CardHeader>
              <CardTitle>项目</CardTitle>
              <CardDescription>会话开在项目的源文件夹里</CardDescription>
              <CardAction><Button variant='outline' size='sm' onClick={() => openProjectDialog()}><FolderPlus className='size-3.5' />添加项目</Button></CardAction>
            </CardHeader>
            <CardContent>
              {projects.length === 0 ? <div className='py-6 text-center text-sm text-muted-foreground'>还没有项目</div> : (
                <div className='grid gap-3 sm:grid-cols-2'>
                  {projects.map((p) => (
                    <div key={p.id} className='group flex items-start gap-3 rounded-lg border p-3 transition-colors hover:bg-muted/40'>
                      <span className='flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground'><FolderOpen className='size-4' /></span>
                      <div className='min-w-0 flex-1'>
                        <div className='truncate font-medium'>{p.name}</div>
                        <div className='truncate font-mono text-[11px] text-muted-foreground' title={p.folders.join('\n')}>
                          {p.folders[0]}{p.folders.length > 1 ? ` +${p.folders.length - 1}` : ''}
                        </div>
                        <div className='mt-1 flex flex-wrap gap-1 text-[11px] text-muted-foreground'>
                          <span>{projConvs(p.id)} 段对话</span>
                          {p.preset && <Badge variant='muted' className='h-4 px-1 text-[10px]'>{p.preset}</Badge>}
                          {p.instructions && <Badge variant='muted' className='h-4 px-1 text-[10px]'>有指令</Badge>}
                        </div>
                      </div>
                      <Button variant='ghost' size='icon' className='size-8 shrink-0' title='在这个项目里开新聊天' onClick={() => navigate(`/new?project=${p.id}`)}>
                        <SquarePen className='size-4' />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>用量分布</CardTitle>
              <CardDescription>开着的会话各用了多少 token</CardDescription>
            </CardHeader>
            <CardContent>
              {usageRows.length === 0 ? <div className='py-6 text-center text-sm text-muted-foreground'>还没有用量</div> : (
                <ul className='space-y-3'>
                  {usageRows.map((s) => {
                    const u = s.usage!
                    const w = (n: number) => `${(n / maxTok) * 100}%`
                    return (
                      <li key={s.id} className='space-y-1'>
                        <div className='flex items-baseline justify-between gap-2 text-xs'>
                          <span className='truncate'>{s.title ?? '新聊天'}</span>
                          <span className='num shrink-0 text-muted-foreground'>{fmtTokens(tok(u))}</span>
                        </div>
                        <div className='flex h-2 overflow-hidden rounded-full bg-muted'>
                          <span className='bg-chart-1' style={{ width: w(u.input) }} />
                          <span className='bg-chart-2' style={{ width: w(u.cacheRead) }} />
                          <span className='bg-chart-3' style={{ width: w(u.cacheWrite) }} />
                          <span className='bg-chart-4' style={{ width: w(u.output) }} />
                        </div>
                      </li>
                    )
                  })}
                  <li className='flex flex-wrap gap-3 pt-1 text-[11px] text-muted-foreground'>
                    {[['bg-chart-1', '输入'], ['bg-chart-2', '缓存读'], ['bg-chart-3', '缓存写'], ['bg-chart-4', '输出']].map(([c, l]) => (
                      <span key={l} className='flex items-center gap-1'><span className={`size-2 rounded-sm ${c}`} />{l}</span>
                    ))}
                  </li>
                </ul>
              )}
            </CardContent>
          </Card>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>最近的对话</CardTitle>
            <CardDescription>存在本机 bitcask 里，共 {convs.length} 段</CardDescription>
            <CardAction><Button variant='outline' size='sm' asChild><Link href='/history'>全部</Link></Button></CardAction>
          </CardHeader>
          <CardContent>
            {convs.length === 0 ? <div className='py-6 text-center text-sm text-muted-foreground'>还没有存下的对话</div> : (
              <Table>
                <TableHeader><TableRow><TableHead>标题</TableHead><TableHead className='text-end'>消息</TableHead><TableHead className='text-end'>更新</TableHead><TableHead /></TableRow></TableHeader>
                <TableBody>
                  {convs.slice(0, 8).map((c) => (
                    <TableRow key={c.id}>
                      <TableCell className='max-w-96 truncate font-medium'>{c.title ?? `对话 #${c.id}`}</TableCell>
                      <TableCell className='num text-end text-muted-foreground'>{c.messages}</TableCell>
                      <TableCell className='text-end text-muted-foreground'>{relTime(c.updated)}</TableCell>
                      <TableCell className='w-20 text-end'><Button variant='ghost' size='sm' onClick={() => open.mutate(c.id)}>打开</Button></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </Page>
    </>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className='flex items-center justify-between gap-3'><span className='shrink-0 text-muted-foreground'>{label}</span><span className='min-w-0 truncate'>{children}</span></div>
}
