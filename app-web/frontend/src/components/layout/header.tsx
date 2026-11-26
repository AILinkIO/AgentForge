import type { ReactNode } from 'react'
import { Moon, Search, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { SidebarTrigger } from '@/components/ui/sidebar'
import { useTheme } from '@/context/theme-provider'
import { useConnected } from '@/lib/events'
import { cn } from '@/lib/utils'
import { openCommandMenu } from './command-menu'
import { ThemeCustomizer } from './theme-customizer'

/** 顶栏：侧栏开关 | 标题 | 搜索（⌘K）· 连接状态 · 明暗 · 界面设置。 */
export function Header({ title, children }: { title?: ReactNode; children?: ReactNode }) {
  const theme = useTheme()
  const connected = useConnected()
  return (
    <header className='sticky top-0 z-20 flex h-14 shrink-0 items-center gap-2 border-b bg-background/80 px-3 backdrop-blur md:px-4 rounded-t-[inherit]'>
      <SidebarTrigger className='-ms-1' />
      <Separator orientation='vertical' className='me-1 data-[orientation=vertical]:h-4' />
      <div className='flex min-w-0 flex-1 items-center gap-2 text-sm font-medium'>{title}{children}</div>
      <Button variant='outline' size='sm' onClick={openCommandMenu}
        className='hidden h-8 w-56 justify-start gap-2 bg-muted/40 font-normal text-muted-foreground shadow-none md:flex'>
        <Search className='size-3.5' />搜索…
        <kbd className='ms-auto rounded border bg-background px-1.5 font-mono text-[10px]'>⌘K</kbd>
      </Button>
      <Button variant='ghost' size='icon' className='size-8 md:hidden' onClick={openCommandMenu}><Search className='size-4' /></Button>
      <span title={connected ? '实时连接正常' : '实时连接断开，正在重连'}
        className={cn('flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px]',
          connected ? 'text-success' : 'bg-warning-soft text-warning')}>
        <span className={cn('size-1.5 rounded-full', connected ? 'bg-success' : 'animate-pulse bg-warning')} />
        <span className='hidden sm:inline'>{connected ? '已连接' : '重连中'}</span>
      </span>
      <Button variant='ghost' size='icon' className='size-8' title='切换明暗'
        onClick={() => theme.set({ mode: theme.theme === 'dark' ? 'light' : 'dark' })}>
        {theme.theme === 'dark' ? <Sun className='size-4' /> : <Moon className='size-4' />}
      </Button>
      <ThemeCustomizer />
    </header>
  )
}
