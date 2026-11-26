import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plug, RefreshCw, Search } from 'lucide-react'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Empty, Page } from '@/components/common/page'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { api, qk } from '@/lib/api'
import { toolLook } from '../chat/tool-icons'

const MCP_STATUS: Record<string, { label: string; v: 'success' | 'warning' | 'danger' | 'muted' }> = {
  CONNECTED: { label: '已连接', v: 'success' },
  CONNECTING: { label: '连接中', v: 'warning' },
  NEEDS_AUTH: { label: '要授权', v: 'warning' },
  FAILED: { label: '失败', v: 'danger' },
  DISABLED: { label: '已停用', v: 'muted' },
}

/** 工具（agent 手上有什么）与 MCP 服务器。 */
export function ToolsPage() {
  const qc = useQueryClient()
  const [q, setQ] = useState('')
  const { data: tools = [] } = useQuery({ queryKey: qk.tools, queryFn: () => api.tools() })
  const { data: mcp = [] } = useQuery({ queryKey: qk.mcp, queryFn: api.mcp })
  const reconnect = useMutation({
    mutationFn: (name: string) => api.mcpReconnect(name),
    onSuccess: (d) => { qc.setQueryData(qk.mcp, d); qc.invalidateQueries({ queryKey: qk.tools }) },
    onError: (e: Error) => toast.error(e.message),
  })
  const shown = tools.filter((t) => !q || t.name.includes(q) || t.description.includes(q))

  return (
    <>
      <Header title='工具与 MCP' />
      <Page title='工具与 MCP' description='只读工具从不问你；会改东西、会上网的按权限设置决定要不要问。'>
        <Tabs defaultValue='tools'>
          <TabsList>
            <TabsTrigger value='tools'>工具 <span className='ms-1 text-muted-foreground'>{tools.length}</span></TabsTrigger>
            <TabsTrigger value='mcp'>MCP 服务器 <span className='ms-1 text-muted-foreground'>{mcp.length}</span></TabsTrigger>
          </TabsList>
          <TabsContent value='tools' className='mt-4 space-y-4'>
            <div className='relative max-w-xs'>
              <Search className='absolute start-2.5 top-2.5 size-4 text-muted-foreground' />
              <Input className='ps-8' placeholder='过滤' value={q} onChange={(e) => setQ(e.target.value)} />
            </div>
            <div className='grid gap-3 md:grid-cols-2 xl:grid-cols-3'>
              {shown.map((t) => {
                const { icon: Icon } = toolLook(t.name)
                return (
                  <Card key={t.name} className='gap-2 py-4'>
                    <CardHeader className='px-4'>
                      <CardTitle className='flex items-center gap-2 font-mono text-sm'>
                        <Icon className='size-4 text-muted-foreground' />{t.name}
                        <span className='ms-auto flex gap-1 font-sans'>
                          {t.readOnly ? <Badge variant='success'>只读</Badge> : t.sensitive ? <Badge variant='warning'>需审批</Badge> : <Badge variant='muted'>写</Badge>}
                          {!t.enabled && <Badge variant='outline'>未启用</Badge>}
                        </span>
                      </CardTitle>
                    </CardHeader>
                    <CardContent className='space-y-2 px-4'>
                      <p className='line-clamp-3 text-xs text-muted-foreground' title={plain(t.description)}>{plain(t.description)}</p>
                      {t.params.length > 0 && (
                        <div className='flex flex-wrap gap-1'>
                          {t.params.map((p) => (
                            <code key={p.name} title={p.description} className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${p.required ? 'bg-primary-soft text-primary' : 'bg-muted text-muted-foreground'}`}>
                              {p.name}{p.required ? '' : '?'}
                            </code>
                          ))}
                        </div>
                      )}
                    </CardContent>
                  </Card>
                )
              })}
            </div>
          </TabsContent>
          <TabsContent value='mcp' className='mt-4'>
            {mcp.length === 0 ? (
              <Empty icon={<Plug className='size-5' />} title='没有 MCP 服务器'>在 <code className='font-mono'>~/.config/agentforge/mcp.json</code> 里配置，重启后连上；它们的工具会出现在工具表里。</Empty>
            ) : (
              <div className='grid gap-3 md:grid-cols-2'>
                {mcp.map((m) => {
                  const st = MCP_STATUS[m.status] ?? { label: m.status, v: 'muted' as const }
                  return (
                    <Card key={m.name} className='gap-2 py-4'>
                      <CardHeader className='px-4'>
                        <CardTitle className='flex items-center gap-2 text-sm'>
                          <Plug className='size-4 text-muted-foreground' />{m.name}
                          <Badge variant={st.v} className='ms-auto'>{st.label}</Badge>
                          <Button variant='ghost' size='icon' className='size-7' title='重连' disabled={reconnect.isPending} onClick={() => reconnect.mutate(m.name)}>
                            <RefreshCw className='size-3.5' />
                          </Button>
                        </CardTitle>
                        {m.error && <CardDescription className='text-destructive'>{m.error}</CardDescription>}
                      </CardHeader>
                      <CardContent className='px-4'>
                        <div className='flex flex-wrap gap-1'>
                          {m.tools.map((t) => <code key={t} className='rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]'>{t}</code>)}
                          {m.tools.length === 0 && <span className='text-xs text-muted-foreground'>没有工具</span>}
                        </div>
                      </CardContent>
                    </Card>
                  )
                })}
              </div>
            )}
          </TabsContent>
        </Tabs>
      </Page>
    </>
  )
}

/** 工具说明是写给模型看的 markdown：卡片里只要纯文字。 */
function plain(s: string): string {
  return s.replace(/\*\*(.+?)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1')
}
