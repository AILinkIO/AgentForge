import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, Archive, Ellipsis, Folder, Loader2, PanelRight, Pencil, Sparkles } from 'lucide-react'
import { openProjectDialog } from '@/features/projects/project-dialog'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { StatusDot } from '@/components/common/status-dot'
import { api, qk } from '@/lib/api'
import { onAgentEvent, seedLive, useLive } from '@/lib/events'
import { STATUS_LABEL } from '@/lib/format'
import { ApprovalCard } from './approval-card'
import { Composer } from './composer'
import { QuestionCard } from './question-card'
import { SidePanel } from './side-panel'
import { Transcript } from './transcript'

const SUGGESTIONS = ['看看这个项目是做什么的', '找出最近改动里可能的 bug', '给 README 补一节快速上手', '跑一下测试，修掉失败的']

export function ChatPage({ id }: { id: string }) {
  const qc = useQueryClient()
  const { data: snap, error } = useQuery({ queryKey: qk.snapshot(id), queryFn: () => api.snapshot(id), retry: 1 })
  const { data: projects = [] } = useQuery({ queryKey: qk.projects, queryFn: api.projects })
  const live = useLive(id)
  const scroller = useRef<HTMLDivElement>(null)
  const [stick, setStick] = useState(true)
  const [side, setSide] = useState(false)
  const [editing, setEditing] = useState(false)

  useEffect(() => { if (snap) seedLive(snap) }, [snap])

  // 压缩是异步的：结果用提示条说清楚（成了压掉几条 / 没成为什么）
  useEffect(() => onAgentEvent((e) => {
    if (e.session !== id) return
    if (e.type === 'compacted') toast.success(`上下文已压缩：${e.headMessages} 条折成一份 ${e.summaryChars} 字的前情提要`)
    if (e.type === 'compact-failed') toast.error(`压缩没成：${e.message}`)
  }), [id])

  // 贴底：在底部时新内容自动跟上；用户往上翻了就不打扰
  useLayoutEffect(() => {
    const el = scroller.current
    if (el && stick) el.scrollTop = el.scrollHeight
  }, [snap, live?.text, stick])
  const onScroll = () => {
    const el = scroller.current
    if (el) setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 80)
  }

  const send = useMutation({ mutationFn: (t: string) => api.send(id, t), onError: (e: Error) => toast.error(e.message) })
  const compact = useMutation({
    mutationFn: () => api.compact(id),
    onSuccess: () => toast('开始压缩上下文'),
    onError: (e: Error) => toast.error(e.message),
  })
  const rename = useMutation({
    mutationFn: (title: string) => api.settings(id, { title }),
    onSuccess: (s) => { qc.setQueryData(qk.snapshot(id), s); setEditing(false) },
    onError: (e: Error) => toast.error(e.message),
  })

  if (error) {
    return (
      <>
        <Header title='会话' />
        <div className='p-8 text-sm text-muted-foreground'>这段会话不在了（可能已经关掉）。到「历史对话」里能重新打开。</div>
      </>
    )
  }
  if (!snap) return <><Header title='会话' /><div className='flex flex-1 items-center justify-center'><Loader2 className='size-5 animate-spin text-muted-foreground' /></div></>

  const s = snap.session
  const project = projects.find((p) => p.id === s.project)
  const title = s.title ?? '新会话'
  const empty = snap.entries.length === 0 && !s.busy
  const approvalFrom = snap.approval?.session && snap.approval.session !== s.id
    ? snap.children.find((c) => c.id === snap.approval?.session)?.agent ?? '子 agent' : undefined

  return (
    <>
      <Header>
        {project && (
          <>
            <button type='button' onClick={() => openProjectDialog(project)} title={`编辑项目「${project.name}」`} data-testid='chat-project'
              className='hidden shrink-0 items-center gap-1.5 rounded-md px-1.5 py-0.5 text-muted-foreground hover:bg-muted hover:text-foreground sm:flex'>
              <Folder className='size-3.5' />{project.name}
              {project.folders.length > 1 && <span className='font-mono text-xs'>/ {snap.root.split(/[\\/]/).filter(Boolean).pop()}</span>}
            </button>
            <span className='hidden text-muted-foreground/50 sm:inline'>/</span>
          </>
        )}
        <StatusDot status={s.status} attention={s.pendingApproval || s.pendingQuestion} />
        {editing ? (
          <form className='flex-1' onSubmit={(e) => { e.preventDefault(); rename.mutate(String(new FormData(e.currentTarget).get('t') ?? '')) }}>
            <Input name='t' autoFocus defaultValue={s.title ?? ''} className='h-8 max-w-sm' onBlur={() => setEditing(false)} />
          </form>
        ) : (
          <button type='button' className='group flex min-w-0 items-center gap-1.5 truncate' onClick={() => setEditing(true)} title='改标题'>
            <span className='truncate'>{title}</span>
            <Pencil className='size-3 shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100' />
          </button>
        )}
        <span data-testid='session-status' data-status={s.status} data-busy={s.busy ? 'true' : 'false'} className='hidden shrink-0 text-xs font-normal text-muted-foreground sm:inline'>{STATUS_LABEL[s.status]}</span>
        <div className='ms-auto flex shrink-0 items-center'>
          <Button variant='ghost' size='icon' className='size-8 xl:hidden' title='上下文与计划' onClick={() => setSide(true)}><PanelRight className='size-4' /></Button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild><Button variant='ghost' size='icon' className='size-8'><Ellipsis className='size-4' /></Button></DropdownMenuTrigger>
            <DropdownMenuContent align='end'>
              <DropdownMenuItem onClick={() => setEditing(true)}><Pencil />改标题</DropdownMenuItem>
              <DropdownMenuItem disabled={s.busy} onClick={() => compact.mutate()}><Archive />压缩上下文</DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem disabled className='font-mono text-[11px]'>{s.id.slice(0, 18)}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </Header>

      <div className='flex min-h-0 flex-1'>
        <div className='relative flex min-w-0 flex-1 flex-col'>
          <div ref={scroller} onScroll={onScroll} className='min-h-0 flex-1 overflow-y-auto'>
            <div className='mx-auto w-full max-w-3xl px-4 py-6 md:px-6'>
              {empty ? (
                <div className='flex flex-col items-center gap-6 pt-[12vh] text-center'>
                  <div className='flex size-12 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-sm'><Sparkles className='size-6' /></div>
                  <div className='space-y-1.5'>
                    <h2 className='text-xl font-semibold tracking-tight'>今天要做什么？</h2>
                    <p className='text-sm text-muted-foreground'>在 <code className='rounded bg-muted px-1.5 py-0.5 font-mono text-xs'>{snap.root}</code> 里读代码、跑命令、改文件</p>
                  </div>
                  <div className='grid w-full max-w-xl gap-2 sm:grid-cols-2'>
                    {SUGGESTIONS.map((t) => (
                      <button key={t} type='button' onClick={() => send.mutate(t)}
                        className='rounded-xl border bg-card px-4 py-3 text-start text-sm transition-colors hover:bg-muted'>{t}</button>
                    ))}
                  </div>
                </div>
              ) : (
                <Transcript snap={snap} live={live} />
              )}
              {s.busy && !live?.text && !snap.approval && !snap.question && (
                <div className='mt-4 flex items-center gap-2 text-xs text-muted-foreground'>
                  <Loader2 className='size-3.5 animate-spin' /><span className='shimmer-text'>{STATUS_LABEL[s.status]}…</span>
                </div>
              )}
              {snap.error && !s.busy && (
                <div className='mt-4 rounded-lg border border-destructive/30 bg-destructive-soft px-3 py-2 text-xs text-destructive'>{snap.error}</div>
              )}
              {snap.approval && <div className='mt-4'><ApprovalCard key={snap.approval.id} session={s.id} req={snap.approval} from={approvalFrom} /></div>}
              {snap.question && <div className='mt-4'><QuestionCard key={snap.question.toolUseId ?? 'q'} session={s.id} req={snap.question} /></div>}
            </div>
          </div>
          {!stick && (
            <Button variant='outline' size='icon' className='absolute bottom-36 left-1/2 size-8 -translate-x-1/2 rounded-full shadow-md'
              onClick={() => { setStick(true); const el = scroller.current; if (el) el.scrollTop = el.scrollHeight }}>
              <ArrowDown className='size-4' />
            </Button>
          )}
          <div className='mx-auto w-full max-w-3xl px-4 pb-4 md:px-6'>
            {s.parentId ? <div className='rounded-xl border bg-muted/40 px-4 py-3 text-center text-xs text-muted-foreground'>这是子 agent 的会话，只能看</div>
              : <Composer snap={snap} />}
          </div>
        </div>
        <aside className='hidden w-80 shrink-0 overflow-y-auto border-s p-4 xl:block'>
          <SidePanel snap={snap} />
        </aside>
      </div>
      <Sheet open={side} onOpenChange={setSide}>
        <SheetContent className='w-80 overflow-y-auto sm:max-w-80'>
          <SheetHeader><SheetTitle>上下文与计划</SheetTitle></SheetHeader>
          <div className='px-4 pb-4'><SidePanel snap={snap} /></div>
        </SheetContent>
      </Sheet>
    </>
  )
}
