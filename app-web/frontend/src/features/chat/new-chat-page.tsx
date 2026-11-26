import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useSearch } from 'wouter'
import { ArrowUp, Check, ChevronDown, Folder, FolderOpen, FolderPlus, Loader2, Monitor, Sparkles, TriangleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { openProjectDialog } from '@/features/projects/project-dialog'
import { api, qk } from '@/lib/api'
import { cn } from '@/lib/utils'

const LAST_KEY = 'agentforge-last-project'
const SUGGESTIONS = ['看看这个项目是做什么的', '找出最近改动里可能的 bug', '给 README 补一节快速上手', '跑一下测试，修掉失败的']
const basename = (p: string) => p.split(/[\\/]/).filter(Boolean).pop() ?? p

/**
 * 新聊天（照 Codex 桌面端）：输入框上方两枚标签选「在哪台机器」「哪个项目 / 哪个源文件夹」，发第一句话时才建会话。
 */
export function NewChatPage() {
  const qc = useQueryClient()
  const [, navigate] = useLocation()
  const search = useSearch()
  const want = new URLSearchParams(search).get('project')
  const { data: projects, isLoading } = useQuery({ queryKey: qk.projects, queryFn: api.projects })
  const { data: info } = useQuery({ queryKey: qk.info, queryFn: api.info })
  const { data: models } = useQuery({ queryKey: qk.models, queryFn: api.models })
  const [pid, setPid] = useState<string | null>(null)
  const [folder, setFolder] = useState<string | null>(null)
  const [model, setModel] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const ref = useRef<HTMLTextAreaElement>(null)

  // 选哪个项目：地址里给的 → 上次用的 → 第一个
  useEffect(() => {
    if (!projects?.length) { setPid(null); return }
    const last = localStorage.getItem(LAST_KEY)
    const pick = [want, pid, last].find((id) => id && projects.some((p) => p.id === id)) ?? projects[0].id
    setPid(pick)
  }, [projects, want]) // eslint-disable-line react-hooks/exhaustive-deps
  const project = projects?.find((p) => p.id === pid)
  useEffect(() => {
    setFolder(project?.folders[0] ?? null)
    setModel(project?.model ?? null)
  }, [project?.id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { ref.current?.focus() }, [project?.id])
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(240, Math.max(56, el.scrollHeight))}px`
  }, [text])

  const effectiveModel = model ?? info?.model ?? ''
  const missing = folder != null && project?.missing.includes(folder)

  const start = async (t: string) => {
    const msg = t.trim()
    if (!msg || !project || busy) return
    setBusy(true)
    try {
      const s = await api.createSession({ project: project.id, folder: folder ?? undefined })
      // 只有在这页上明确选过（或项目指定了）才改；否则由服务器按当前默认定 —— 页面手里那份默认可能是旧的
      if (model && model !== s.model) await api.settings(s.id, { model })
      await api.send(s.id, msg)
      localStorage.setItem(LAST_KEY, project.id)
      qc.invalidateQueries({ queryKey: qk.sessions })
      navigate(`/chat/${s.id}`)
    } catch (e) {
      toast.error((e as Error).message)
      setBusy(false)
    }
  }

  const byProvider = new Map<string, string[]>()
  for (const m of models?.models ?? []) byProvider.set(m.provider, [...(byProvider.get(m.provider) ?? []), m.ref])

  if (isLoading) return <><Header title='新聊天' /><div className='flex flex-1 items-center justify-center'><Loader2 className='size-5 animate-spin text-muted-foreground' /></div></>

  if (!projects?.length) {
    return (
      <>
        <Header title='新聊天' />
        <div className='flex flex-1 flex-col items-center justify-center gap-5 p-6 text-center'>
          <div className='flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground'><FolderPlus className='size-6' /></div>
          <div className='space-y-1.5'>
            <h2 className='text-xl font-semibold tracking-tight'>先添加一个项目</h2>
            <p className='max-w-md text-sm text-muted-foreground'>项目就是服务器上的一个或几个源文件夹。会话开在项目里，agent 在那里读代码、跑命令、改文件。</p>
          </div>
          <Button onClick={() => openProjectDialog()} data-testid='empty-add-project'><FolderPlus className='size-4' />添加项目</Button>
        </div>
      </>
    )
  }

  return (
    <>
      <Header title='新聊天' />
      <div className='flex min-h-0 flex-1 flex-col overflow-y-auto'>
        <div className='mx-auto flex w-full max-w-3xl flex-1 flex-col px-4 md:px-6'>
          <div className='flex flex-1 flex-col items-center justify-center gap-6 py-10 text-center'>
            <div className='flex size-12 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-sm'><Sparkles className='size-6' /></div>
            <div className='space-y-1.5'>
              <h2 className='text-2xl font-semibold tracking-tight'>要在「{project?.name}」里做些什么？</h2>
              <p className='text-sm text-muted-foreground'><code className='rounded bg-muted px-1.5 py-0.5 font-mono text-xs'>{folder}</code></p>
            </div>
            <div className='grid w-full max-w-xl gap-2 sm:grid-cols-2'>
              {SUGGESTIONS.map((t) => (
                <button key={t} type='button' disabled={busy || missing} onClick={() => start(t)}
                  className='rounded-xl border bg-card px-4 py-3 text-start text-sm transition-colors hover:bg-muted disabled:opacity-50'>{t}</button>
              ))}
            </div>
          </div>

          <div className='sticky bottom-0 pb-4'>
            {/* 输入框上方的标签：机器 · 项目 · 源文件夹 */}
            <div className='flex flex-wrap items-center gap-1 rounded-t-2xl border border-b-0 bg-muted/50 px-3 py-1.5 text-sm'>
              <span className='flex items-center gap-1.5 rounded-md px-2 py-1 text-muted-foreground' title='会话跑在这台服务器上'>
                <Monitor className='size-4' />{info?.host ?? '服务器'}
              </span>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button type='button' data-testid='pick-project' className='flex items-center gap-1.5 rounded-md px-2 py-1 hover:bg-background'>
                    <Folder className='size-4 text-muted-foreground' />{project?.name}<ChevronDown className='size-3.5 text-muted-foreground' />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align='start' className='w-64'>
                  <DropdownMenuLabel>项目</DropdownMenuLabel>
                  {projects.map((p) => (
                    <DropdownMenuItem key={p.id} onClick={() => { setPid(p.id); navigate(`/new?project=${p.id}`, { replace: true }) }}>
                      <Folder />{p.name}{p.id === pid && <Check className='ms-auto' />}
                    </DropdownMenuItem>
                  ))}
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => openProjectDialog()}><FolderPlus />添加项目…</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              {project && project.folders.length > 1 && (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <button type='button' data-testid='pick-folder' className='flex items-center gap-1.5 rounded-md px-2 py-1 hover:bg-background'>
                      <FolderOpen className='size-4 text-muted-foreground' />{folder ? basename(folder) : ''}<ChevronDown className='size-3.5 text-muted-foreground' />
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align='start' className='w-80'>
                    <DropdownMenuLabel>在哪个源文件夹里开</DropdownMenuLabel>
                    {project.folders.map((f) => (
                      <DropdownMenuItem key={f} onClick={() => setFolder(f)} className='font-mono text-xs'>
                        <FolderOpen />{f}{f === folder && <Check className='ms-auto' />}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
              {missing && <span className='flex items-center gap-1 text-xs text-warning'><TriangleAlert className='size-3.5' />这个文件夹不在了</span>}
            </div>
            <div className='rounded-b-2xl border bg-card shadow-sm focus-within:ring-1 focus-within:ring-ring/50'>
              <textarea ref={ref} rows={2} value={text} onChange={(e) => setText(e.target.value)} disabled={busy}
                data-testid='new-chat-input' placeholder='让它做点什么…（Enter 发送，Shift+Enter 换行）'
                className='block max-h-60 min-h-14 w-full resize-none bg-transparent px-4 pt-3 pb-1 text-sm leading-relaxed outline-none placeholder:text-muted-foreground'
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); start(text) } }} />
              <div className='flex items-center gap-1.5 px-2 pb-2'>
                <Select value={effectiveModel} onValueChange={setModel}>
                  <SelectTrigger size='sm' className='h-8 max-w-64 border-0 bg-transparent font-mono text-xs shadow-none hover:bg-muted'>
                    <SelectValue placeholder='选模型' />
                  </SelectTrigger>
                  <SelectContent>
                    {[...byProvider.entries()].map(([p, refs]) => (
                      <SelectGroup key={p}>
                        <SelectLabel>{p}</SelectLabel>
                        {refs.map((r) => <SelectItem key={r} value={r} className='font-mono text-xs'>{r.slice(p.length + 1)}</SelectItem>)}
                      </SelectGroup>
                    ))}
                  </SelectContent>
                </Select>
                {project?.preset && <span className='rounded-md bg-muted px-2 py-1 text-xs text-muted-foreground'>项目权限：{project.preset}</span>}
                <Button size='icon' data-testid='new-chat-send' className={cn('ms-auto size-8 rounded-full', !text.trim() && 'opacity-40')}
                  disabled={!text.trim() || busy || missing} onClick={() => start(text)} title='发送'>
                  {busy ? <Loader2 className='size-4 animate-spin' /> : <ArrowUp className='size-4' />}
                </Button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
