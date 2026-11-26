import { useQueryClient } from '@tanstack/react-query'
import { Route, Switch } from 'wouter'
import { AppSidebar } from '@/components/layout/app-sidebar'
import { CommandMenu } from '@/components/layout/command-menu'
import { Header } from '@/components/layout/header'
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar'
import { Toaster } from '@/components/ui/sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { useAgentEvents } from '@/lib/events'
import { AgentsPage } from '@/features/agents/agents-page'
import { ChatPage } from '@/features/chat/chat-page'
import { NewChatPage } from '@/features/chat/new-chat-page'
import { ProjectDialog } from '@/features/projects/project-dialog'
import { HistoryPage } from '@/features/history/history-page'
import { OverviewPage } from '@/features/overview/overview-page'
import { ModelsPage } from '@/features/settings/models-page'
import { CredentialsPage } from '@/features/settings/credentials-page'
import { PermissionsPage } from '@/features/settings/permissions-page'
import { ToolsPage } from '@/features/settings/tools-page'

export function App() {
  const qc = useQueryClient()
  useAgentEvents(qc)
  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider>
        <AppSidebar />
        {/* 内嵌侧栏时主区是一张圆角卡片：限高到视口，内部各自滚动（聊天页要输入框钉在底部） */}
        <SidebarInset className='flex h-svh flex-col overflow-hidden md:peer-data-[variant=inset]:h-[calc(100svh-1rem)]'>
          <Switch>
            <Route path='/'><Scroll><OverviewPage /></Scroll></Route>
            <Route path='/new'><NewChatPage /></Route>
            <Route path='/chat/:id'>{(p) => <ChatPage key={p.id} id={p.id} />}</Route>
            <Route path='/agents'><Scroll><AgentsPage /></Scroll></Route>
            <Route path='/history'><Scroll><HistoryPage /></Scroll></Route>
            <Route path='/models'><Scroll><ModelsPage /></Scroll></Route>
            <Route path='/credentials'><Scroll><CredentialsPage /></Scroll></Route>
            <Route path='/permissions'><Scroll><PermissionsPage /></Scroll></Route>
            <Route path='/tools'><Scroll><ToolsPage /></Scroll></Route>
            <Route><Header title='找不到' /><div className='p-8 text-sm text-muted-foreground'>没有这个页面。</div></Route>
          </Switch>
        </SidebarInset>
      </SidebarProvider>
      <CommandMenu />
      <ProjectDialog />
      <Toaster position='top-center' />
    </TooltipProvider>
  )
}

/** 普通页面：头部钉住，内容区滚动。 */
function Scroll({ children }: { children: React.ReactNode }) {
  return <div className='flex min-h-0 flex-1 flex-col overflow-y-auto [&>header]:shrink-0'>{children}</div>
}
