import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@/context/theme-provider'
import { App } from './app'
import '@fontsource-variable/inter'
import './styles/index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 实时变化靠 SSE 推来的失效通知，不靠轮询
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <App />
      </ThemeProvider>
    </QueryClientProvider>
  </StrictMode>,
)
