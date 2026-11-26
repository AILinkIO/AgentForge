import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation } from 'wouter'
import {
  Bot, Boxes, Ellipsis, Folder, FolderOpen, FolderPlus, History, KeyRound, LayoutDashboard, MessageSquare, Plus, ShieldCheck,
  SquarePen, Wrench, X,
} from 'lucide-react'
import { toast } from 'sonner'
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupAction, SidebarGroupContent, SidebarGroupLabel,
  SidebarHeader, SidebarMenu, SidebarMenuBadge, SidebarMenuButton, SidebarMenuItem, SidebarMenuSub, SidebarMenuSubButton,
  SidebarMenuSubItem, SidebarRail,
} from '@/components/ui/sidebar'
import { StatusDot } from '@/components/common/status-dot'
import { useTheme } from '@/context/theme-provider'
import { openProjectDialog } from '@/features/projects/project-dialog'
import { api, qk } from '@/lib/api'
import type { Conversation, Project, SessionSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const SHOWN = 5
const COLLAPSED_KEY = 'agentforge-collapsed-projects'

function loadCollapsed(): string[] {
  try { return JSON.parse(localStorage.getItem(COLLAPSED_KEY) || '[]') } catch { return [] }
}

/** 侧栏（照 Codex 桌面端）：新聊天 · 导航 · 项目（每个项目底下是它的对话）· 设置。 */
export function AppSidebar() {
  const [loc, navigate] = useLocation()
  const qc = useQueryClient()
  const { sidebar } = useTheme()
  const { data: info } = useQuery({ queryKey: qk.info, queryFn: api.info })
  const { data: sessions = [] } = useQuery({ queryKey: qk.sessions, queryFn: api.sessions })
  const { data: projects = [] } = useQuery({ queryKey: qk.projects, queryFn: api.projects })
  const { data: convs = [] } = useQuery({ queryKey: qk.conversations, queryFn: () => api.conversations(1000) })
  const { data: perms } = useQuery({ queryKey: qk.permissions, queryFn: api.permissions })
  const [collapsed, setCollapsed] = useState<string[]>(loadCollapsed)
  const [expanded, setExpanded] = useState<string[]>([])

  const toggle = (id: string) => {
    const next = collapsed.includes(id) ? collapsed.filter((x) => x !== id) : [...collapsed, id]
    setCollapsed(next)
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify(next))
  }

  const open = useMutation({
    mutationFn: (conv: number) => api.openConversation(conv),
    onSuccess: (s) => { qc.invalidateQueries({ queryKey: qk.sessions }); navigate(`/chat/${s.id}`) },
    onError: (e: Error) => toast.error(e.message),
  })
  const close = useMutation({
    mutationFn: (id: string) => api.closeSession(id),
    onSuccess: (_, id) => {
      qc.invalidateQueries({ queryKey: qk.sessions })
      qc.invalidateQueries({ queryKey: qk.conversations })
      if (loc === `/chat/${id}`) navigate('/new')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const running = sessions.reduce((n, s) => n + (s.busy ? 1 : 0) + s.runningSubagents, 0)
  const openConvs = new Set(sessions.map((s) => s.conversation).filter((c): c is number => c != null))
  const known = new Set(projects.map((p) => p.id))
  const loose = sessions.filter((s) => !s.project || !known.has(s.project))

  const nav = [
    { href: '/', label: '概览', icon: LayoutDashboard },
    { href: '/agents', label: 'Agents', icon: Bot, badge: running || undefined },
    { href: '/history', label: '历史对话', icon: History },
  ]
  const settings = [
    { href: '/models', label: '模型', icon: Boxes },
    { href: '/credentials', label: '凭证', icon: KeyRound },
    { href: '/permissions', label: '权限与沙盒', icon: ShieldCheck },
    { href: '/tools', label: '工具与 MCP', icon: Wrench },
  ]

  const sessionItem = (s: SessionSummary) => (
    <SidebarMenuSubItem key={s.id} className='group/chat relative' data-testid='sidebar-chat'>
      <SidebarMenuSubButton asChild isActive={loc === `/chat/${s.id}`}>
        <Link href={`/chat/${s.id}`}>
          <span className='flex-1 truncate'>{s.title ?? '新聊天'}</span>
          <StatusDot status={s.status} attention={s.pendingApproval || s.pendingQuestion} className='me-4' />
        </Link>
      </SidebarMenuSubButton>
      <button type='button' title='关掉' data-testid='close-chat' onClick={() => close.mutate(s.id)}
        className='absolute end-1 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground opacity-0 hover:bg-sidebar-accent hover:text-foreground group-hover/chat:opacity-100'>
        <X className='size-3.5' />
      </button>
    </SidebarMenuSubItem>
  )

  const convItem = (c: Conversation) => (
    <SidebarMenuSubItem key={`c${c.id}`} data-testid='sidebar-conv'>
      <SidebarMenuSubButton asChild>
        <button type='button' onClick={() => open.mutate(c.id)} className='w-full text-sidebar-foreground/80'>
          <span className='truncate'>{c.title || `对话 #${c.id}`}</span>
        </button>
      </SidebarMenuSubButton>
    </SidebarMenuSubItem>
  )

  const projectItem = (p: Project) => {
    const live = sessions.filter((s) => s.project === p.id)
    const stored = convs.filter((c) => c.project === p.id && !c.parent && !openConvs.has(c.id))
    const isCollapsed = collapsed.includes(p.id)
    const all = [...live.map(sessionItem), ...stored.map(convItem)]
    const showAll = expanded.includes(p.id)
    const active = loc === `/new?project=${p.id}` || live.some((s) => loc === `/chat/${s.id}`)
    return (
      <SidebarMenuItem key={p.id} data-testid='sidebar-project' data-project={p.name}>
        <SidebarMenuButton onClick={() => toggle(p.id)} tooltip={p.name} className={cn('pe-14', active && 'font-medium')}>
          {isCollapsed ? <Folder /> : <FolderOpen />}
          <span className='truncate'>{p.name}</span>
        </SidebarMenuButton>
        <div className='absolute end-1 top-1.5 flex gap-0.5 opacity-0 transition-opacity group-hover/menu-item:opacity-100 group-data-[collapsible=icon]:hidden'>
          <button type='button' title={`在「${p.name}」里开新聊天`} data-testid='project-new-chat'
            onClick={() => navigate(`/new?project=${p.id}`)} className='rounded p-1 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground'>
            <SquarePen className='size-3.5' />
          </button>
          <button type='button' title='编辑项目' data-testid='project-edit' onClick={() => openProjectDialog(p)}
            className='rounded p-1 text-muted-foreground hover:bg-sidebar-accent hover:text-foreground'>
            <Ellipsis className='size-3.5' />
          </button>
        </div>
        {!isCollapsed && all.length > 0 && (
          <SidebarMenuSub>
            {showAll ? all : all.slice(0, SHOWN)}
            {all.length > SHOWN && (
              <SidebarMenuSubItem>
                <SidebarMenuSubButton asChild>
                  <button type='button' className='w-full text-muted-foreground'
                    onClick={() => setExpanded(showAll ? expanded.filter((x) => x !== p.id) : [...expanded, p.id])}>
                    {showAll ? '收起' : `展开显示（还有 ${all.length - SHOWN} 段）`}
                  </button>
                </SidebarMenuSubButton>
              </SidebarMenuSubItem>
            )}
          </SidebarMenuSub>
        )}
      </SidebarMenuItem>
    )
  }

  return (
    <Sidebar collapsible='icon' variant={sidebar}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size='lg' asChild>
              <Link href='/'>
                <div className='flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground'>
                  <svg viewBox='0 0 32 32' className='size-4'><path d='M9 23 16 8l7 15M11.5 18h9' fill='none' stroke='currentColor' strokeWidth='3' strokeLinecap='round' strokeLinejoin='round' /></svg>
                </div>
                <div className='grid flex-1 text-start text-sm leading-tight'>
                  <span className='truncate font-semibold'>AgentForge</span>
                  <span className='truncate text-xs text-muted-foreground'>{info?.host ?? '…'}</span>
                </div>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
          <SidebarMenuItem>
            <SidebarMenuButton asChild isActive={loc.startsWith('/new')} tooltip='新聊天'>
              <Link href='/new' data-testid='new-chat'><SquarePen /><span>新聊天</span></Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
          {nav.map((n) => (
            <SidebarMenuItem key={n.href}>
              <SidebarMenuButton asChild isActive={loc === n.href} tooltip={n.label}>
                <Link href={n.href}><n.icon /><span>{n.label}</span></Link>
              </SidebarMenuButton>
              {n.badge != null && <SidebarMenuBadge className='bg-info-soft text-info'>{n.badge}</SidebarMenuBadge>}
            </SidebarMenuItem>
          ))}
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>项目</SidebarGroupLabel>
          <SidebarGroupAction title='添加项目' data-testid='add-project' onClick={() => openProjectDialog()}>
            <Plus /><span className='sr-only'>添加项目</span>
          </SidebarGroupAction>
          <SidebarGroupContent>
            <SidebarMenu>
              {projects.length === 0 && (
                <SidebarMenuItem>
                  <SidebarMenuButton onClick={() => openProjectDialog()} tooltip='添加项目'>
                    <FolderPlus /><span className='text-muted-foreground'>添加第一个项目</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              )}
              {projects.map(projectItem)}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {loose.length > 0 && (
          <SidebarGroup>
            <SidebarGroupLabel>其他会话</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {loose.map((s) => (
                  <SidebarMenuItem key={s.id} className='group/chat'>
                    <SidebarMenuButton asChild isActive={loc === `/chat/${s.id}`} tooltip={s.title ?? '新聊天'}>
                      <Link href={`/chat/${s.id}`}>
                        <MessageSquare />
                        <span className='flex-1 truncate'>{s.title ?? '新聊天'}</span>
                        <StatusDot status={s.status} attention={s.pendingApproval || s.pendingQuestion} className='me-4' />
                      </Link>
                    </SidebarMenuButton>
                    <button type='button' title='关掉' onClick={() => close.mutate(s.id)}
                      className='absolute end-1 top-2 rounded p-0.5 text-muted-foreground opacity-0 hover:text-foreground group-hover/chat:opacity-100 group-data-[collapsible=icon]:hidden'>
                      <X className='size-3.5' />
                    </button>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        )}

        <SidebarGroup className='mt-auto'>
          <SidebarGroupLabel>设置</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {settings.map((n) => (
                <SidebarMenuItem key={n.href}>
                  <SidebarMenuButton asChild isActive={loc === n.href} tooltip={n.label}>
                    <Link href={n.href}><n.icon /><span>{n.label}</span></Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>

      <SidebarFooter>
        <Link href='/permissions'
          className='block rounded-lg border bg-background/60 p-3 text-xs transition-colors hover:bg-sidebar-accent group-data-[collapsible=icon]:hidden'>
          <div className='mb-1.5 flex items-center gap-1.5 font-medium'>
            <ShieldCheck className='size-3.5 text-primary' />
            {perms?.presets.find((p) => p.id === perms.preset)?.label ?? '自定义权限'}
          </div>
          <div className='line-clamp-2 text-muted-foreground'>{perms?.sandboxStatus ?? '…'}</div>
          <div className='mt-2 flex items-center gap-1.5 truncate text-muted-foreground'>
            <KeyRound className='size-3' /><span className='truncate font-mono'>{info?.model ?? '没选模型'}</span>
          </div>
        </Link>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
