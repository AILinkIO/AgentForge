import type { ReactNode } from 'react'
import { useTheme } from '@/context/theme-provider'
import { cn } from '@/lib/utils'

/** 页面骨架：标题行（标题 + 说明 + 右侧操作）+ 内容；宽度跟主题设置里的「内容宽度」走。 */
export function Page({ title, description, actions, children, className }: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  const { content } = useTheme()
  return (
    <div className={cn('flex flex-col gap-6 p-4 md:p-6', content === 'centered' && 'mx-auto w-full max-w-6xl', className)}>
      <div className='flex flex-wrap items-end justify-between gap-3'>
        <div className='space-y-1'>
          <h1 className='text-xl font-semibold tracking-tight md:text-2xl'>{title}</h1>
          {description && <p className='text-sm text-muted-foreground'>{description}</p>}
        </div>
        {actions && <div className='flex items-center gap-2'>{actions}</div>}
      </div>
      {children}
    </div>
  )
}

export function Empty({ icon, title, children }: { icon?: ReactNode; title: ReactNode; children?: ReactNode }) {
  return (
    <div className='flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed px-6 py-14 text-center'>
      {icon && <div className='mb-1 flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground'>{icon}</div>}
      <div className='font-medium'>{title}</div>
      {children && <div className='max-w-md text-sm text-muted-foreground'>{children}</div>}
    </div>
  )
}
