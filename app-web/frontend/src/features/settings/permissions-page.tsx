import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, ShieldCheck, ShieldOff, Zap } from 'lucide-react'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Page } from '@/components/common/page'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { api, qk } from '@/lib/api'
import { cn } from '@/lib/utils'

const ICON: Record<string, typeof Eye> = { 'read-only': Eye, auto: Zap, 'full-access': ShieldOff }

/** 权限与沙盒（Codex 的 AskForApproval × SandboxPolicy，三档预设）。 */
export function PermissionsPage() {
  const qc = useQueryClient()
  const { data: p } = useQuery({ queryKey: qk.permissions, queryFn: api.permissions })
  const set = useMutation({
    mutationFn: (preset: string) => api.setPreset(preset),
    onSuccess: (d) => { qc.setQueryData(qk.permissions, d); qc.invalidateQueries({ queryKey: qk.info }); toast(`权限：${d.describe}`) },
    onError: (e: Error) => toast.error(e.message),
  })
  return (
    <>
      <Header title='权限与沙盒' />
      <Page title='权限与沙盒' description='agent 什么时候要问你、命令在多大的沙盒里跑。对所有会话生效，并记成默认。'>
        <div className='grid gap-4 md:grid-cols-3'>
          {(p?.presets ?? []).map((x) => {
            const Icon = ICON[x.id] ?? ShieldCheck
            const on = p?.preset === x.id
            return (
              <button key={x.id} type='button' disabled={set.isPending} onClick={() => set.mutate(x.id)}
                className={cn('flex flex-col gap-3 rounded-xl border bg-card p-5 text-start shadow-xs transition hover:border-primary/50',
                  on && 'border-primary ring-1 ring-primary', x.id === 'full-access' && !on && 'hover:border-destructive/50')}>
                <div className='flex items-center gap-2'>
                  <span className={cn('flex size-9 items-center justify-center rounded-lg', on ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground')}>
                    <Icon className='size-4.5' />
                  </span>
                  <div className='font-semibold'>{x.label}</div>
                  {on && <Badge className='ms-auto'>当前</Badge>}
                </div>
                <p className='text-sm text-muted-foreground'>{x.description}</p>
                <div className='mt-auto flex flex-wrap gap-1.5 pt-1'>
                  <Badge variant='muted' className='font-mono'>approval={x.approval}</Badge>
                  <Badge variant='muted' className='font-mono'>sandbox={x.sandbox}</Badge>
                </div>
              </button>
            )
          })}
        </div>
        <Card>
          <CardHeader>
            <CardTitle>当前设置</CardTitle>
            <CardDescription>{p?.describe}</CardDescription>
          </CardHeader>
          <CardContent className='grid gap-4 text-sm md:grid-cols-2'>
            <dl className='space-y-2'>
              <Item k='审批策略' v={<code className='font-mono text-xs'>{p?.approval}</code>} />
              <Item k='沙盒' v={<code className='font-mono text-xs'>{p?.sandbox}</code>} />
              <Item k='网络' v={p?.network ? <Badge variant='warning'>可出网</Badge> : <Badge variant='muted'>断网</Badge>} />
              <Item k='另加的可写目录' v={p?.writableRoots.length ? p.writableRoots.join('、') : '无'} />
            </dl>
            <div className='rounded-lg bg-muted/50 p-3 text-xs leading-relaxed text-muted-foreground'>
              <div className='mb-1 font-medium text-foreground'>沙盒后端</div>
              {p?.sandboxStatus}
              <div className='mt-2'>更细的规则（命令前缀放行 / 拒绝、网络主机）写在 <code className='font-mono'>~/.config/agentforge/rules/*.rules</code> 与项目的 <code className='font-mono'>.agentforge/rules/</code>，审批时选「以后都不再问」也会写进去。</div>
            </div>
          </CardContent>
        </Card>
      </Page>
    </>
  )
}

function Item({ k, v }: { k: string; v: React.ReactNode }) {
  return <div className='flex items-center justify-between gap-4 border-b pb-2 last:border-0'><dt className='text-muted-foreground'>{k}</dt><dd className='text-end'>{v}</dd></div>
}
