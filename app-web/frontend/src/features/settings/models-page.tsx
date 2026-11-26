import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'wouter'
import { Check, CloudDownload, FileUp, KeyRound, Link2, RefreshCw, Search, TriangleAlert } from 'lucide-react'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Empty, Page } from '@/components/common/page'
import { VendorMark } from '@/components/common/vendor-mark'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { api, qk } from '@/lib/api'
import { fmtTime, fmtTokens } from '@/lib/format'
import { cn } from '@/lib/utils'

/** 模型：可用模型（来源 models.dev / providers.json）、默认模型；models.dev 目录的同步与导入（照 LiteGate）。 */
export function ModelsPage() {
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [q, setQ] = useState('')
  const [source, setSource] = useState('all')
  const [provider, setProvider] = useState('all')
  const { data } = useQuery({ queryKey: qk.models, queryFn: api.models })
  const { data: cat } = useQuery({ queryKey: qk.catalog, queryFn: api.catalog })

  const after = (ok: string) => () => {
    for (const k of [qk.models, qk.catalog, qk.credentials, qk.info]) qc.invalidateQueries({ queryKey: k })
    toast.success(ok)
  }
  // 同步在服务器后台跑（几 MB，慢网要一会儿）：这里只负责开个头，进度 / 结果经 SSE 写进 qk.catalog
  const sync = useMutation({
    mutationFn: (url?: string) => api.syncCatalog(url),
    onSuccess: (c) => { qc.setQueryData(qk.catalog, c); setMirrorOpen(false) },
    onError: (e: Error) => toast.error(e.message),
  })
  const [mirrorOpen, setMirrorOpen] = useState(false)
  const [mirror, setMirror] = useState(() => localStorage.getItem('agentforge-catalog-mirror') ?? '')
  const st = cat?.sync
  const prevRunning = useRef(false)
  useEffect(() => {
    if (prevRunning.current && st && !st.running) {
      if (st.ok) toast.success(`同步完成：${cat!.providers} 家厂商、${cat!.models} 个模型`)
      else toast.error('同步失败', { description: st.error })
    }
    prevRunning.current = !!st?.running
  }, [st?.running]) // eslint-disable-line react-hooks/exhaustive-deps
  const imp = useMutation({ mutationFn: (f: File) => api.importCatalog(f), onSuccess: (c) => after(`导入完成：${c.providers} 家厂商、${c.models} 个模型`)(), onError: (e: Error) => toast.error(e.message) })
  const select = useMutation({
    mutationFn: (ref: string) => api.selectModel(ref),
    onSuccess: (d) => { qc.setQueryData(qk.models, d); qc.invalidateQueries({ queryKey: qk.info }); toast(`默认模型：${d.current}`) },
    onError: (e: Error) => toast.error(e.message),
  })

  const all = data?.models ?? []
  const providers = [...new Map(all.map((m) => [m.provider, m.providerLabel ?? m.provider])).entries()]
  const models = all
    .filter((m) => source === 'all' || m.source === source)
    .filter((m) => provider === 'all' || m.provider === provider)
    .filter((m) => !q || m.ref.toLowerCase().includes(q.toLowerCase()) || (m.label ?? '').toLowerCase().includes(q.toLowerCase()))

  const catText = !cat ? '…'
    : cat.source === 'NONE' ? '还没有 models.dev 目录'
      : cat.source === 'BUNDLED' ? `用的是随包的旧目录（${cat.providers} 家厂商）——同步一次拿最新的`
        : `models.dev 同步于 ${fmtTime(cat.syncedAt!)} · ${cat.providers} 家厂商、${cat.models} 个模型`

  return (
    <>
      <Header title='模型' />
      <Page title='模型' description={<>默认 <code className='font-mono text-xs'>{data?.current ?? '没选'}</code>；会话里、项目里都可以单独换。</>}
        actions={<Button variant='outline' asChild><Link href='/credentials'><KeyRound className='size-4' />管理凭证</Link></Button>}>
        {data?.problem && (
          <div className='flex items-start gap-2 rounded-lg border border-warning/40 bg-warning-soft px-3 py-2 text-sm text-warning'>
            <TriangleAlert className='mt-0.5 size-4 shrink-0' />{data.problem}
          </div>
        )}

        <Card className='py-4'>
          <CardContent className='flex flex-wrap items-center gap-4 px-5'>
            <div className={cn('flex size-10 items-center justify-center rounded-lg', cat?.stale ? 'bg-warning-soft text-warning' : 'bg-primary-soft text-primary')}>
              <CloudDownload className='size-5' />
            </div>
            <div className='min-w-0 flex-1'>
              <div className='text-sm font-medium'>模型目录</div>
              <div className='text-xs text-muted-foreground' data-testid='catalog-status'>{catText}{cat?.stale && cat.source === 'CACHE' ? '（超过 7 天了）' : ''}</div>
            </div>
            <Button variant='ghost' onClick={() => setMirrorOpen(true)} disabled={st?.running}><Link2 className='size-4' />其他地址</Button>
            <input ref={fileRef} type='file' accept='application/json,.json' hidden
              onChange={(e) => { const f = e.target.files?.[0]; if (f) imp.mutate(f); e.target.value = '' }} />
            <Button variant='ghost' onClick={() => fileRef.current?.click()} disabled={imp.isPending} title='服务器连不上外网时：在别处下好 https://models.dev/api.json 再传上来'>
              <FileUp className='size-4' />导入 api.json
            </Button>
            <Button onClick={() => sync.mutate(undefined)} disabled={sync.isPending || st?.running} data-testid='catalog-sync'>
              <RefreshCw className={cn('size-4', st?.running && 'animate-spin')} />{st?.running ? '同步中…' : '同步 models.dev'}
            </Button>
          </CardContent>
          {st?.running && (
            <div className='mx-5 mb-1 space-y-1.5' data-testid='catalog-progress'>
              <div className='h-1.5 overflow-hidden rounded-full bg-muted'><div className='h-full w-1/3 animate-pulse rounded-full bg-primary' /></div>
              <div className='flex justify-between text-[11px] text-muted-foreground'>
                <span className='truncate font-mono'>{st.url}</span><span className='num'>已收 {(st.bytes / 1048576).toFixed(1)} MB</span>
              </div>
            </div>
          )}
          {!st?.running && st?.ok === false && (
            <div className='mx-5 mb-1 rounded-lg border border-destructive/30 bg-destructive-soft px-3 py-2.5 text-sm' data-testid='catalog-error'>
              <div className='flex items-start gap-2 text-destructive'>
                <TriangleAlert className='mt-0.5 size-4 shrink-0' />
                <div className='min-w-0'>
                  <div className='font-medium'>同步没成（旧目录还在，照常能用）</div>
                  <div className='break-words text-xs'>{st.error}</div>
                  <div className='mt-1 truncate font-mono text-[11px] opacity-70'>{st.url}</div>
                </div>
              </div>
              <div className='mt-2 flex flex-wrap gap-2 ps-6'>
                <Button size='sm' variant='outline' className='h-7 bg-background' onClick={() => sync.mutate(st.url === cat?.url ? undefined : st.url)}>
                  <RefreshCw className='size-3.5' />重试
                </Button>
                <Button size='sm' variant='outline' className='h-7 bg-background' onClick={() => setMirrorOpen(true)}><Link2 className='size-3.5' />换个地址</Button>
                <Button size='sm' variant='outline' className='h-7 bg-background' onClick={() => fileRef.current?.click()}><FileUp className='size-3.5' />导入 api.json</Button>
                <span className='self-center text-[11px] text-muted-foreground'>服务器要代理才能出网的话，设好 HTTPS_PROXY 再启动</span>
              </div>
            </div>
          )}
        </Card>

        <Dialog open={mirrorOpen} onOpenChange={setMirrorOpen}>
          <DialogContent className='sm:max-w-lg'>
            <DialogHeader>
              <DialogTitle>从其他地址同步</DialogTitle>
              <DialogDescription>给一个能拿到 models.dev 格式 api.json 的地址（镜像、内网文件服务器、对象存储……）。</DialogDescription>
            </DialogHeader>
            <Input autoFocus value={mirror} onChange={(e) => setMirror(e.target.value)} placeholder='https://mirror.example.com/models.dev/api.json' className='font-mono text-xs'
              onKeyDown={(e) => { if (e.key === 'Enter' && mirror.trim()) { localStorage.setItem('agentforge-catalog-mirror', mirror.trim()); sync.mutate(mirror.trim()) } }} />
            <DialogFooter>
              <Button variant='ghost' onClick={() => sync.mutate(undefined)}>还是用 models.dev</Button>
              <Button disabled={!mirror.trim() || sync.isPending} onClick={() => { localStorage.setItem('agentforge-catalog-mirror', mirror.trim()); sync.mutate(mirror.trim()) }}>同步</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        <div className='flex flex-wrap items-center gap-2'>
          <div className='relative w-full max-w-xs'>
            <Search className='absolute start-2.5 top-2.5 size-4 text-muted-foreground' />
            <Input className='ps-8' placeholder='搜索模型' value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <Select value={source} onValueChange={setSource}>
            <SelectTrigger className='w-40' data-testid='model-source'><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>全部来源</SelectItem>
              <SelectItem value='models.dev'>models.dev</SelectItem>
              <SelectItem value='config'>providers.json</SelectItem>
            </SelectContent>
          </Select>
          <Select value={provider} onValueChange={setProvider}>
            <SelectTrigger className='w-44'><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>全部厂商</SelectItem>
              {providers.map(([id, label]) => <SelectItem key={id} value={id}>{label}</SelectItem>)}
            </SelectContent>
          </Select>
          <span className='ms-auto text-xs text-muted-foreground'>{models.length} / {all.length} 个模型</span>
        </div>

        {all.length === 0 ? (
          <Empty icon={<KeyRound className='size-5' />} title='还没有可用的模型'>
            先去<Link href='/credentials' className='text-primary underline'>凭证</Link>里加一家（models.dev 里选或手动填），填上 key 就有了。
          </Empty>
        ) : (
          <Card className='gap-0 overflow-hidden py-0'>
            <Table>
              <TableHeader className='bg-muted/40'>
                <TableRow>
                  <TableHead className='ps-5'>模型</TableHead><TableHead>来源</TableHead><TableHead className='text-end'>窗口</TableHead>
                  <TableHead className='hidden text-end md:table-cell'>最大输出</TableHead><TableHead className='hidden lg:table-cell'>能力</TableHead><TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {models.map((m) => {
                  const cur = m.ref === data?.current
                  return (
                    <TableRow key={m.ref} data-testid='model-row' data-ref={m.ref}>
                      <TableCell className='ps-5'>
                        <div className='flex items-center gap-3'>
                          <VendorMark id={m.provider} label={m.providerLabel} className='size-7 text-xs' />
                          <div className='min-w-0'>
                            <div className='truncate font-medium'>{m.label || m.id}</div>
                            <div className='truncate font-mono text-[11px] text-muted-foreground'>{m.ref}</div>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell><Badge variant={m.source === 'models.dev' ? 'info' : 'outline'}>{m.source === 'models.dev' ? 'models.dev' : 'providers.json'}</Badge></TableCell>
                      <TableCell className='num text-end'>{fmtTokens(m.contextWindow)}</TableCell>
                      <TableCell className='num hidden text-end md:table-cell'>{fmtTokens(m.maxOutput)}</TableCell>
                      <TableCell className='hidden space-x-1 lg:table-cell'>
                        {m.reasons && <Badge variant='muted'>思考</Badge>}
                        {m.attachments && <Badge variant='muted'>图片</Badge>}
                      </TableCell>
                      <TableCell className='w-28 pe-5 text-end'>
                        {cur ? <Badge><Check className='size-3' />默认</Badge>
                          : <Button variant='ghost' size='sm' disabled={select.isPending} onClick={() => select.mutate(m.ref)}>设为默认</Button>}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </Card>
        )}
      </Page>
    </>
  )
}
