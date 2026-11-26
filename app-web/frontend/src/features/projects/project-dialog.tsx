import { useEffect, useState, useSyncExternalStore } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'wouter'
import {
  ChevronRight, Eye, Folder, FolderGit2, FolderOpen, Globe2, ShieldCheck, ShieldOff, Sparkles, TriangleAlert, X, Zap,
} from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { api, qk } from '@/lib/api'
import type { Project } from '@/lib/types'
import { cn } from '@/lib/utils'
import { FolderBrowser } from './folder-picker'

// 全局一个项目对话框：哪里都能 openProjectDialog()（新建）/ openProjectDialog(p)（编辑）
type State = { open: boolean; project?: Project }
let state: State = { open: false }
const subs = new Set<() => void>()
const setState = (s: State) => { state = s; subs.forEach((f) => f()) }
export const openProjectDialog = (project?: Project) => setState({ open: true, project })
const useDialogState = () => useSyncExternalStore((f) => { subs.add(f); return () => subs.delete(f) }, () => state)

const FOLLOW = '__follow__'
const basename = (p: string) => p.split(/[\\/]/).filter(Boolean).pop() ?? p
const parentOf = (p: string) => p.replace(/[\\/][^\\/]+[\\/]?$/, '') || '/'

const PRESET_LOOK: Record<string, { icon: typeof Eye; tone: string }> = {
  [FOLLOW]: { icon: Globe2, tone: 'text-muted-foreground' },
  'read-only': { icon: Eye, tone: 'text-info' },
  auto: { icon: Zap, tone: 'text-success' },
  'full-access': { icon: ShieldOff, tone: 'text-destructive' },
}

/**
 * 新建 / 编辑项目（照 Codex 桌面端的「编辑项目」：名字、源文件夹、移除本地项目），外加项目设置（指令、默认模型、权限预设）。
 * 选文件夹就在对话框里：「建议」列出家目录下的 git 仓库，「浏览」逐级挑。
 */
export function ProjectDialog() {
  const { open, project } = useDialogState()
  const qc = useQueryClient()
  const [loc, navigate] = useLocation()
  const [name, setName] = useState('')
  const [named, setNamed] = useState(false)          // 用户自己改过名字就不再跟着第一个文件夹走
  const [folders, setFolders] = useState<string[]>([])
  const [instructions, setInstructions] = useState('')
  const [model, setModel] = useState(FOLLOW)
  const [preset, setPreset] = useState(FOLLOW)
  const [picking, setPicking] = useState<'suggest' | 'browse' | null>('suggest')
  const [settings, setSettings] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const { data: models } = useQuery({ queryKey: qk.models, queryFn: api.models, enabled: open })
  const { data: perms } = useQuery({ queryKey: qk.permissions, queryFn: api.permissions, enabled: open })
  const { data: info } = useQuery({ queryKey: qk.info, queryFn: api.info, enabled: open })
  const { data: projects = [] } = useQuery({ queryKey: qk.projects, queryFn: api.projects, enabled: open })
  const { data: suggest, isLoading: suggesting } = useQuery({ queryKey: ['fs-suggest'], queryFn: api.fsSuggest, enabled: open, staleTime: 60_000 })

  useEffect(() => {
    if (!open) return
    setName(project?.name ?? '')
    setNamed(!!project)
    setFolders(project?.folders ?? [])
    setInstructions(project?.instructions ?? '')
    setModel(project?.model ?? FOLLOW)
    setPreset(project?.preset ?? FOLLOW)
    setPicking(project ? null : 'suggest')
    setSettings(!!project && !!(project.instructions || project.model || project.preset))
  }, [open, project])

  const close = () => setState({ open: false })
  const add = (p: string) => {
    if (folders.includes(p)) return
    const next = [...folders, p]
    setFolders(next)
    if (!named) setName(basename(next[0]))
    if (!project) setPicking(null)
  }
  const removeFolder = (f: string) => {
    const next = folders.filter((x) => x !== f)
    setFolders(next)
    if (!named) setName(next[0] ? basename(next[0]) : '')
  }
  const body = () => ({
    name: name.trim(), folders, instructions,
    model: model === FOLLOW ? undefined : model, preset: preset === FOLLOW ? undefined : preset,
  })
  const save = useMutation({
    mutationFn: () => project ? api.updateProject(project.id, body()) : api.createProject(body()),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: qk.projects })
      qc.invalidateQueries({ queryKey: ['fs-suggest'] })
      toast.success(project ? `已保存「${p.name}」` : `已添加项目「${p.name}」`)
      close()
      if (!project) navigate(`/new?project=${p.id}`)
    },
    onError: (e: Error) => toast.error(e.message),
  })
  const remove = useMutation({
    mutationFn: () => api.deleteProject(project!.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.projects })
      qc.invalidateQueries({ queryKey: qk.conversations })
      toast(`已移除项目「${project?.name}」（文件夹和对话都没动）`)
      close()
      if (loc.startsWith('/new')) navigate('/new')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const ownerOf = (path: string) => projects.find((p) => p.id !== project?.id && p.folders.includes(path))
  const ready = name.trim() && folders.length > 0
  const presetOptions = [
    { id: FOLLOW, label: '跟随全局', desc: perms?.presets.find((p) => p.id === perms.preset)?.label ?? perms?.describe ?? '' },
    ...(perms?.presets ?? []).map((p) => ({ id: p.id, label: p.label, desc: `${p.approval} · ${p.sandbox}` })),
  ]
  const settingsSummary = [instructions.trim() && '有指令', model !== FOLLOW && model.split('/').pop(),
    preset !== FOLLOW && presetOptions.find((p) => p.id === preset)?.label].filter(Boolean).join(' · ')

  return (
    <>
      <Dialog open={open} onOpenChange={(o) => !o && close()}>
        <DialogContent className='max-h-[92svh] gap-5 overflow-y-auto sm:max-w-2xl' data-testid='project-dialog'>
          <DialogHeader>
            <DialogTitle className='flex items-center gap-2'>
              <span className='flex size-8 items-center justify-center rounded-lg bg-primary-soft text-primary'><FolderOpen className='size-4' /></span>
              {project ? '编辑项目' : '添加项目'}
            </DialogTitle>
            <DialogDescription>项目 = 服务器上的一个或几个源文件夹。会话开在项目里，agent 在那儿读代码、跑命令、改文件；对话也归在项目下。</DialogDescription>
          </DialogHeader>

          {/* 名字 */}
          <div className='flex items-center rounded-xl border bg-background focus-within:ring-2 focus-within:ring-ring/40'>
            <Folder className='mx-3.5 size-4 shrink-0 text-muted-foreground' />
            <Input value={name} onChange={(e) => { setName(e.target.value); setNamed(true) }} placeholder='项目名（缺省取第一个文件夹的名字）'
              data-testid='project-name' className='h-11 border-0 bg-transparent text-base shadow-none focus-visible:ring-0' />
          </div>

          {/* 源文件夹 */}
          <div className='space-y-2.5'>
            <div className='flex items-baseline justify-between'>
              <Label>源文件夹</Label>
              <span className='text-xs text-muted-foreground'>第一个是新会话缺省的工作根</span>
            </div>
            {folders.length === 0 ? (
              <div className='rounded-xl border border-dashed px-4 py-5 text-center text-sm text-muted-foreground'>还没选文件夹——从下面的建议里点一个，或者浏览服务器上的目录</div>
            ) : (
              <ul className='space-y-2'>
                {folders.map((f, i) => (
                  <li key={f} data-testid='project-folder' className='flex items-center gap-3 rounded-xl border bg-card px-3 py-2.5 shadow-xs'>
                    <span className='flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground'><FolderGit2 className='size-4' /></span>
                    <div className='min-w-0 flex-1'>
                      <div className='flex items-center gap-1.5 text-sm font-medium'>
                        {basename(f)}
                        {i === 0 && folders.length > 1 && <span className='rounded bg-primary-soft px-1.5 text-[10px] font-normal text-primary'>缺省</span>}
                        {project?.missing.includes(f) && <span className='flex items-center gap-1 text-[11px] font-normal text-warning'><TriangleAlert className='size-3' />不在了</span>}
                        {ownerOf(f) && <span className='text-[11px] font-normal text-muted-foreground'>（也在「{ownerOf(f)!.name}」里）</span>}
                      </div>
                      <div className='truncate font-mono text-[11px] text-muted-foreground' title={f}>{parentOf(f)}</div>
                    </div>
                    <button type='button' title='移出' onClick={() => removeFolder(f)} className='rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground'>
                      <X className='size-4' />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {picking === null ? (
              <Button variant='outline' size='sm' onClick={() => setPicking('suggest')} data-testid='add-folder'><FolderOpen className='size-3.5' />添加文件夹</Button>
            ) : (
              <div className='space-y-2 rounded-xl border bg-muted/20 p-3'>
                <div className='flex items-center justify-between'>
                  <Tabs value={picking} onValueChange={(v) => setPicking(v as 'suggest' | 'browse')}>
                    <TabsList className='h-8'>
                      <TabsTrigger value='suggest' className='text-xs'><Sparkles className='size-3.5' />建议</TabsTrigger>
                      <TabsTrigger value='browse' className='text-xs'><FolderOpen className='size-3.5' />浏览</TabsTrigger>
                    </TabsList>
                  </Tabs>
                  {folders.length > 0 && <Button variant='ghost' size='sm' className='h-7 text-xs' onClick={() => setPicking(null)}>收起</Button>}
                </div>
                {picking === 'suggest' ? (
                  <div data-testid='folder-suggest'>
                    {suggesting ? <div className='py-6 text-center text-xs text-muted-foreground'>在家目录里找 git 仓库…</div>
                      : !suggest?.repos.length ? (
                        <div className='py-6 text-center text-xs text-muted-foreground'>家目录下两层以内没找到 git 仓库——去「浏览」里挑</div>
                      ) : (
                        <div className='grid max-h-56 gap-1.5 overflow-y-auto sm:grid-cols-2'>
                          {suggest.repos.map((r) => {
                            const on = folders.includes(r.path)
                            return (
                              <button key={r.path} type='button' disabled={on} onClick={() => add(r.path)} data-testid='suggested-folder'
                                className={cn('flex items-center gap-2.5 rounded-lg border bg-background px-2.5 py-2 text-start transition-colors hover:border-primary/40 hover:bg-primary-soft disabled:opacity-50')}>
                                <FolderGit2 className='size-4 shrink-0 text-primary' />
                                <div className='min-w-0'>
                                  <div className='truncate text-sm'>{r.name}</div>
                                  <div className='truncate font-mono text-[10px] text-muted-foreground'>~/{r.parent}{r.used ? ' · 已在项目里' : ''}</div>
                                </div>
                              </button>
                            )
                          })}
                        </div>
                      )}
                  </div>
                ) : (
                  <FolderBrowser onPick={add} chosen={folders} start={folders.length ? parentOf(folders[folders.length - 1]) : undefined} />
                )}
              </div>
            )}
          </div>

          {/* 项目设置 */}
          <div className='rounded-xl border'>
            <button type='button' onClick={() => setSettings(!settings)} data-testid='project-settings-toggle'
              className='flex w-full items-center gap-2 px-4 py-3 text-start text-sm font-medium'>
              <ChevronRight className={cn('size-4 text-muted-foreground transition-transform', settings && 'rotate-90')} />
              项目设置
              <span className='truncate text-xs font-normal text-muted-foreground'>{settingsSummary || '可选：指令、默认模型、权限预设'}</span>
            </button>
            {settings && (
              <div className='space-y-4 border-t px-4 py-4'>
                <div className='space-y-1.5'>
                  <Label htmlFor='pi'>项目指令</Label>
                  <Textarea id='pi' rows={3} value={instructions} onChange={(e) => setInstructions(e.target.value)} data-testid='project-instructions'
                    placeholder='拼进这个项目每段会话的系统提示。比如：用中文回答；改代码前先跑测试；不要动 vendor/ 目录' />
                </div>
                <div className='space-y-1.5'>
                  <Label>默认模型</Label>
                  <Select value={model} onValueChange={setModel}>
                    <SelectTrigger className='w-full' data-testid='project-model'><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value={FOLLOW}>跟随全局（{info?.model ?? '未选'}）</SelectItem>
                      {models?.models.map((m) => <SelectItem key={m.ref} value={m.ref} className='font-mono text-xs'>{m.ref}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className='space-y-1.5'>
                  <Label>权限预设</Label>
                  <div className='grid gap-2 sm:grid-cols-4' role='radiogroup'>
                    {presetOptions.map((p) => {
                      const look = PRESET_LOOK[p.id] ?? { icon: ShieldCheck, tone: 'text-muted-foreground' }
                      const on = preset === p.id
                      return (
                        <button key={p.id} type='button' role='radio' aria-checked={on} onClick={() => setPreset(p.id)} data-testid='project-preset'
                          className={cn('flex flex-col items-start gap-1 rounded-lg border px-3 py-2.5 text-start transition-colors hover:bg-muted/60',
                            on && 'border-primary bg-primary-soft ring-1 ring-primary')}>
                          <span className='flex items-center gap-1.5 text-sm font-medium'><look.icon className={cn('size-3.5', look.tone)} />{p.label}</span>
                          <span className='line-clamp-2 font-mono text-[10px] text-muted-foreground'>{p.desc}</span>
                        </button>
                      )
                    })}
                  </div>
                </div>
              </div>
            )}
          </div>

          <DialogFooter className='gap-2 sm:justify-between'>
            {project ? (
              <Button variant='ghost' className='bg-destructive-soft text-destructive hover:bg-destructive/15 hover:text-destructive' onClick={() => setConfirmRemove(true)}>
                移除项目
              </Button>
            ) : <span />}
            <div className='flex gap-2'>
              <Button variant='ghost' onClick={close}>取消</Button>
              <Button disabled={!ready || save.isPending} onClick={() => save.mutate()} data-testid='project-save'>{project ? '保存' : '添加项目'}</Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <AlertDialog open={confirmRemove} onOpenChange={setConfirmRemove}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>移除项目「{project?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>只从列表里拿掉：文件夹里的文件不动，存着的对话也留着（归到「未归类」）。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction className='bg-destructive text-white hover:bg-destructive/90' onClick={() => remove.mutate()}>移除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
