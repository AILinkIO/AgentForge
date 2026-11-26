import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { FileDiff, Globe, KeyRound, ShieldAlert, SquareTerminal, Wrench } from 'lucide-react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/lib/api'
import type { ApprovalRequest } from '@/lib/types'
import { cn } from '@/lib/utils'
import { DiffView } from './markdown'

const KIND: Record<ApprovalRequest['kind'], { label: string; icon: typeof Wrench }> = {
  EXEC: { label: '要运行命令', icon: SquareTerminal },
  EXEC_RETRY: { label: '沙盒拦下了，要不带沙盒重跑吗', icon: ShieldAlert },
  PATCH: { label: '要改文件', icon: FileDiff },
  NETWORK: { label: '要访问网络', icon: Globe },
  TOOL: { label: '要调用工具', icon: Wrench },
  SECRET: { label: '要读密钥文件', icon: KeyRound },
  REVIEW: { label: '这一轮的改动', icon: FileDiff },
}

/** 审批卡（Codex 的审批面板）：说清要做什么、为什么问，选项照后端给的顺序摆；字母键直接选。 */
export function ApprovalCard({ session, req, from }: { session: string; req: ApprovalRequest; from?: string }) {
  const [feedback, setFeedback] = useState('')
  const [denying, setDenying] = useState(false)
  const k = KIND[req.kind]
  const decide = useMutation({
    mutationFn: (d: { decision: string; feedback?: string }) => api.approve(session, req.id, d.decision, d.feedback),
    onError: (e: Error) => toast.error(e.message),
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t.closest('textarea, input, [contenteditable]') || e.metaKey || e.ctrlKey || e.altKey) return
      const c = req.choices.find((c) => c.key === e.key.toLowerCase())
      if (!c || decide.isPending) return
      e.preventDefault()
      if (c.decision === 'DENIED') setDenying(true)
      else decide.mutate({ decision: c.decision })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [req, decide])

  const danger = req.kind === 'EXEC_RETRY' || req.kind === 'SECRET'
  return (
    <div data-testid='approval-card' data-kind={req.kind} className={cn('overflow-hidden rounded-xl border-2 bg-card shadow-sm', danger ? 'border-destructive/40' : 'border-warning/50')}>
      <div className={cn('flex items-center gap-2 px-4 py-2.5 text-sm font-medium', danger ? 'bg-destructive-soft text-destructive' : 'bg-warning-soft text-warning')}>
        <k.icon className='size-4' />{k.label}
        {req.toolName && <Badge variant='outline' className='ms-1 border-current/30 font-mono text-[10px] text-current'>{req.toolName}</Badge>}
        {from && <span className='ms-auto text-xs font-normal opacity-80'>来自 {from}</span>}
      </div>
      <div className='space-y-3 px-4 py-3'>
        {req.command ? (
          <pre className='overflow-x-auto rounded-md bg-muted px-3 py-2 font-mono text-xs whitespace-pre-wrap break-all'>
            <span className='text-muted-foreground select-none'>$ </span>{req.command.join(' ')}
          </pre>
        ) : req.title && <div className='text-sm font-medium break-words'>{req.title}</div>}
        {req.cwd && <div className='text-xs text-muted-foreground'>在 <code className='font-mono'>{req.cwd}</code></div>}
        {req.retryReason && <div className='text-xs text-destructive'>{req.retryReason}</div>}
        {req.reason && <div className='rounded-md border-s-2 border-warning bg-muted/40 px-3 py-1.5 text-xs text-subtle-foreground'>{req.reason}</div>}
        {req.preview?.text && (
          <div className='max-h-80 overflow-auto rounded-md border'>
            {req.preview.kind === 'DIFF' ? <DiffView text={req.preview.text} className='py-2' />
              : <pre className='px-3 py-2 font-mono text-xs whitespace-pre-wrap break-all'>{req.preview.text}</pre>}
          </div>
        )}
        {denying ? (
          <div className='space-y-2'>
            <Textarea autoFocus value={feedback} onChange={(e) => setFeedback(e.target.value)} rows={2}
              placeholder='（可选）告诉它该怎么做，它会照着换个做法'
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); decide.mutate({ decision: 'DENIED', feedback }) } }} />
            <div className='flex gap-2'>
              <Button size='sm' variant='destructive' disabled={decide.isPending} onClick={() => decide.mutate({ decision: 'DENIED', feedback })}>不做，接着干别的</Button>
              <Button size='sm' variant='ghost' onClick={() => setDenying(false)}>返回</Button>
            </div>
          </div>
        ) : (
          <div className='flex flex-wrap gap-2'>
            {req.choices.map((c, i) => {
              const approve = c.decision.startsWith('APPROVED')
              return (
                <Button key={c.decision} size='sm' disabled={decide.isPending}
                  variant={i === 0 && approve ? 'default' : c.decision === 'ABORT' ? 'outline' : approve ? 'secondary' : 'outline'}
                  className={cn(!approve && c.decision !== 'ABORT' && 'text-destructive hover:text-destructive')}
                  onClick={() => c.decision === 'DENIED' ? setDenying(true) : decide.mutate({ decision: c.decision })}>
                  {c.label}
                  <kbd className='ms-1 rounded border border-current/20 px-1 font-mono text-[10px] opacity-60'>{c.key}</kbd>
                </Button>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
