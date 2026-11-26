import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'wouter'
import { Bot, Boxes, FolderPlus, History, KeyRound, LayoutDashboard, MessageSquarePlus, MessagesSquare, Moon, ShieldCheck, Sun, Wrench } from 'lucide-react'
import { toast } from 'sonner'
import {
  CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandSeparator, CommandShortcut,
} from '@/components/ui/command'
import { useTheme } from '@/context/theme-provider'
import { openProjectDialog } from '@/features/projects/project-dialog'
import { api, qk } from '@/lib/api'

let openMenu: (() => void) | null = null
export const openCommandMenu = () => openMenu?.()

/** ⌘K / Ctrl+K：跳页面、切会话、开新会话、换明暗。 */
export function CommandMenu() {
  const [open, setOpen] = useState(false)
  const [, navigate] = useLocation()
  const qc = useQueryClient()
  const theme = useTheme()
  const { data: sessions = [] } = useQuery({ queryKey: qk.sessions, queryFn: api.sessions })
  const { data: convs = [] } = useQuery({ queryKey: qk.conversations, queryFn: () => api.conversations(30), enabled: open })

  useEffect(() => {
    openMenu = () => setOpen(true)
    const down = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpen((o) => !o)
      }
    }
    document.addEventListener('keydown', down)
    return () => { document.removeEventListener('keydown', down); openMenu = null }
  }, [])

  const go = (f: () => void) => { setOpen(false); f() }

  return (
    <CommandDialog open={open} onOpenChange={setOpen} title='命令面板' description='搜页面、会话、命令'>
      <CommandInput placeholder='输入命令或搜索…' />
      <CommandList>
        <CommandEmpty>没有匹配的结果</CommandEmpty>
        <CommandGroup heading='操作'>
          <CommandItem onSelect={() => go(() => navigate('/new'))}>
            <MessageSquarePlus />新聊天
          </CommandItem>
          <CommandItem onSelect={() => go(() => openProjectDialog())}>
            <FolderPlus />添加项目
          </CommandItem>
          <CommandItem onSelect={() => go(() => theme.set({ mode: theme.theme === 'dark' ? 'light' : 'dark' }))}>
            {theme.theme === 'dark' ? <Sun /> : <Moon />}切换明暗
          </CommandItem>
        </CommandGroup>
        {sessions.length > 0 && (
          <CommandGroup heading='开着的会话'>
            {sessions.map((s) => (
              <CommandItem key={s.id} value={`session ${s.title ?? ''} ${s.id}`} onSelect={() => go(() => navigate(`/chat/${s.id}`))}>
                <MessagesSquare />{s.title ?? '新会话'}
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        <CommandSeparator />
        <CommandGroup heading='页面'>
          {[
            { href: '/', label: '概览', icon: LayoutDashboard },
            { href: '/agents', label: 'Agents', icon: Bot },
            { href: '/history', label: '历史对话', icon: History },
            { href: '/models', label: '模型', icon: Boxes },
            { href: '/credentials', label: '凭证', icon: KeyRound },
            { href: '/permissions', label: '权限与沙盒', icon: ShieldCheck },
            { href: '/tools', label: '工具与 MCP', icon: Wrench },
          ].map((p) => (
            <CommandItem key={p.href} onSelect={() => go(() => navigate(p.href))}><p.icon />{p.label}</CommandItem>
          ))}
        </CommandGroup>
        {convs.length > 0 && (
          <CommandGroup heading='最近的对话'>
            {convs.slice(0, 12).map((c) => (
              <CommandItem key={c.id} value={`conv ${c.title ?? ''} ${c.id}`} onSelect={() => go(async () => {
                try {
                  const s = await api.openConversation(c.id)
                  qc.invalidateQueries({ queryKey: qk.sessions })
                  navigate(`/chat/${s.id}`)
                } catch (e) { toast.error((e as Error).message) }
              })}>
                <History />{c.title ?? `对话 #${c.id}`}<CommandShortcut>{c.messages} 条</CommandShortcut>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
      </CommandList>
    </CommandDialog>
  )
}
