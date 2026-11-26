import { cn } from '@/lib/utils'
import type { SessionStatus } from '@/lib/types'

/** 会话状态的小圆点：忙 = 跳动，等你 = 琥珀色，出错 = 红。 */
export function StatusDot({ status, attention, className }: { status: SessionStatus; attention?: boolean; className?: string }) {
  const busy = status === 'THINKING' || status === 'STREAMING' || status === 'COMPACTING'
  const color = attention || status === 'AWAITING_APPROVAL' || status === 'AWAITING_QUESTION'
    ? 'bg-warning'
    : status === 'ERROR' ? 'bg-destructive' : busy ? 'bg-info' : 'bg-muted-foreground/40'
  return (
    <span className={cn('relative inline-flex size-2 shrink-0', className)}>
      {(busy || attention) && <span className={cn('absolute inline-flex size-full animate-ping rounded-full opacity-60', color)} />}
      <span className={cn('relative inline-flex size-2 rounded-full', color)} />
    </span>
  )
}
