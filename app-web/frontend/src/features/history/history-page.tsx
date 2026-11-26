import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'wouter'
import { History, Search, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Empty, Page } from '@/components/common/page'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { api, qk } from '@/lib/api'
import { fmtTime, relTime } from '@/lib/format'

/** 存着的对话：过滤 / 全文搜索、打开、删除。 */
export function HistoryPage() {
  const qc = useQueryClient()
  const [, navigate] = useLocation()
  const [q, setQ] = useState('')
  const [search, setSearch] = useState('')
  const [doomed, setDoomed] = useState<number | null>(null)
  const { data: convs = [], isLoading } = useQuery({ queryKey: qk.conversations, queryFn: () => api.conversations(1000) })
  const { data: projects = [] } = useQuery({ queryKey: qk.projects, queryFn: api.projects })
  const [proj, setProj] = useState('all')
  const known = new Map(projects.map((p) => [p.id, p.name]))
  const { data: hits, isFetching } = useQuery({ queryKey: ['search', search], queryFn: () => api.search(search), enabled: search.length >= 3 })

  const open = useMutation({
    mutationFn: (conv: number) => api.openConversation(conv),
    onSuccess: (s) => { qc.invalidateQueries({ queryKey: qk.sessions }); navigate(`/chat/${s.id}`) },
    onError: (e: Error) => toast.error(e.message),
  })
  const remove = useMutation({
    mutationFn: (conv: number) => api.deleteConversation(conv),
    onSuccess: () => { qc.invalidateQueries({ queryKey: qk.conversations }); toast('删掉了') },
    onError: (e: Error) => toast.error(e.message),
  })

  const filtered = convs
    .filter((c) => !c.parent)
    .filter((c) => proj === 'all' || (proj === 'none' ? !c.project || !known.has(c.project) : c.project === proj))
    .filter((c) => !q || (c.title ?? '').toLowerCase().includes(q.toLowerCase()))

  return (
    <>
      <Header title='历史对话' />
      <Page title='历史对话' description={`共 ${convs.length} 段，存在本机 bitcask 里`}>
        <div className='flex flex-wrap gap-2'>
          <div className='relative w-full max-w-xs'>
            <Search className='absolute start-2.5 top-2.5 size-4 text-muted-foreground' />
            <Input className='ps-8' placeholder='按标题过滤' value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <Select value={proj} onValueChange={setProj}>
            <SelectTrigger className='w-44' data-testid='history-project'><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value='all'>全部项目</SelectItem>
              {projects.map((p) => <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>)}
              <SelectItem value='none'>未归类</SelectItem>
            </SelectContent>
          </Select>
          <form className='flex gap-2' onSubmit={(e) => { e.preventDefault(); setSearch(String(new FormData(e.currentTarget).get('s') ?? '').trim()) }}>
            <Input name='s' className='w-64' placeholder='全文搜索（至少 3 个字）' />
            <Button type='submit' variant='secondary'>搜索</Button>
          </form>
        </div>

        {search.length >= 3 && (
          <Card className='py-3'>
            <CardContent className='px-4'>
              <div className='mb-2 text-sm font-medium'>「{search}」的搜索结果 {isFetching ? '…' : `（${hits?.length ?? 0}）`}
                <button type='button' className='ms-2 text-xs font-normal text-muted-foreground hover:text-foreground' onClick={() => setSearch('')}>清除</button>
              </div>
              <ul className='divide-y'>
                {(hits ?? []).map((h, i) => (
                  <li key={i} className='flex items-start gap-3 py-2 text-sm'>
                    <div className='min-w-0 flex-1'>
                      <div className='font-medium'>{h.title ?? `对话 #${h.conversation}`}</div>
                      <div className='line-clamp-2 text-xs text-muted-foreground'><span className='me-1 rounded bg-muted px-1'>{h.role}</span>{h.snippet}</div>
                    </div>
                    <Button variant='ghost' size='sm' onClick={() => open.mutate(h.conversation)}>打开</Button>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {!isLoading && convs.length === 0 ? <Empty icon={<History className='size-5' />} title='还没有存下的对话'>会话每轮结束都会自动存盘。</Empty> : (
          <Card className='py-0'>
            <Table>
              <TableHeader><TableRow><TableHead className='ps-4'>标题</TableHead><TableHead className='hidden md:table-cell'>项目</TableHead><TableHead className='text-end'>消息</TableHead><TableHead className='hidden text-end md:table-cell'>创建</TableHead><TableHead className='text-end'>更新</TableHead><TableHead /></TableRow></TableHeader>
              <TableBody>
                {filtered.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className='max-w-[28rem] truncate ps-4 font-medium'>{c.title ?? `对话 #${c.id}`}</TableCell>
                    <TableCell className='hidden max-w-40 truncate text-muted-foreground md:table-cell' title={c.root}>
                      {c.project && known.has(c.project) ? known.get(c.project) : <span className='text-muted-foreground/60'>未归类</span>}
                    </TableCell>
                    <TableCell className='num text-end text-muted-foreground'>{c.messages}</TableCell>
                    <TableCell className='hidden text-end text-muted-foreground md:table-cell'>{fmtTime(c.created)}</TableCell>
                    <TableCell className='text-end text-muted-foreground'>{relTime(c.updated)}</TableCell>
                    <TableCell className='w-28 pe-4 text-end'>
                      <Button variant='ghost' size='sm' onClick={() => open.mutate(c.id)}>打开</Button>
                      <Button variant='ghost' size='icon' className='size-8 text-muted-foreground hover:text-destructive' onClick={() => setDoomed(c.id)}><Trash2 className='size-4' /></Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      </Page>
      <AlertDialog open={doomed != null} onOpenChange={(o) => !o && setDoomed(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删掉这段对话？</AlertDialogTitle>
            <AlertDialogDescription>删了就找不回来了。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction className='bg-destructive text-white hover:bg-destructive/90' onClick={() => { if (doomed != null) remove.mutate(doomed); setDoomed(null) }}>删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
