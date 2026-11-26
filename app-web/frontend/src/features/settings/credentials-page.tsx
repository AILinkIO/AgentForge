import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'wouter'
import { KeyRound, Pencil, Plus, Trash2, TriangleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { Header } from '@/components/layout/header'
import { Empty, Page } from '@/components/common/page'
import { VendorMark } from '@/components/common/vendor-mark'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { api, qk } from '@/lib/api'
import type { Credential } from '@/lib/types'
import { CredentialDialog } from './credential-dialog'

/** 凭证（照 LiteGate 的「上游凭证」）：用到的每一家 provider、它的协议 / 端点 / key 来路 / 模型数。 */
export function CredentialsPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: qk.credentials, queryFn: api.credentials })
  const [editing, setEditing] = useState<Credential | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Credential | null>(null)
  const remove = useMutation({
    mutationFn: (id: string) => api.removeCredential(id),
    onSuccess: (list) => {
      qc.setQueryData(qk.credentials, list)
      qc.invalidateQueries({ queryKey: qk.models })
      qc.invalidateQueries({ queryKey: qk.info })
      toast('已移除')
    },
    onError: (e: Error) => toast.error(e.message),
  })

  return (
    <>
      <Header title='凭证' />
      <Page title='凭证' description='模型服务的协议、地址与 API Key。key 优先取环境变量，其次是加密存进配置目录的那份。'
        actions={<Button onClick={() => setEditing('new')} data-testid='add-credential'><Plus className='size-4' />添加凭证</Button>}>
        {isLoading ? null : !data?.length ? (
          <Empty icon={<KeyRound className='size-5' />} title='还没有凭证'>
            从 models.dev 目录里选一家填上 key，或者手动填写自建网关的地址；之后它的模型就出现在<Link href='/models' className='text-primary underline'>模型</Link>里。
          </Empty>
        ) : (
          <div className='overflow-hidden rounded-xl border bg-card shadow-xs'>
            {data.map((c, i) => (
              <div key={c.id} data-testid='credential-row' data-id={c.id}
                className={`flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4 ${i > 0 ? 'border-t' : ''}`}>
                <div className='flex min-w-0 flex-1 basis-80 items-start gap-3'>
                  <VendorMark id={c.id} label={c.label} />
                  <div className='min-w-0 space-y-0.5'>
                    <div className='flex flex-wrap items-center gap-1.5'>
                      <span className='font-semibold'>{c.label}</span>
                      <span className='font-mono text-xs text-muted-foreground'>{c.id}</span>
                      <Badge variant='muted'>{c.api === 'anthropic' ? 'Anthropic' : 'OpenAI 兼容'}</Badge>
                      <Badge variant={c.source === 'catalog' ? 'info' : 'outline'}>{c.source === 'catalog' ? 'models.dev' : 'providers.json'}</Badge>
                    </div>
                    <div className='truncate font-mono text-xs text-muted-foreground'>{c.baseUrl ?? '（SDK 缺省端点）'}</div>
                    <div className='flex items-center gap-1.5 text-xs text-muted-foreground'>
                      {c.keySource ? (
                        <>Key <span className='font-mono'>{c.keyHint}</span> · {c.keySource === 'env' ? <>环境变量 <code className='font-mono'>{c.keyEnv}</code></> : '加密存储'}</>
                      ) : (
                        <span className='flex items-center gap-1 text-warning'><TriangleAlert className='size-3.5' />没有 key：它的模型不会出现</span>
                      )}
                    </div>
                  </div>
                </div>
                <div className='text-sm'>
                  <div className='num text-lg font-semibold'>{c.models}</div>
                  <div className='text-xs text-muted-foreground'>可用模型{c.source === 'catalog' && c.catalogModels > c.models ? ` / 目录 ${c.catalogModels}` : ''}</div>
                </div>
                <div className='ms-auto flex items-center gap-1'>
                  <Button variant='ghost' size='icon' title='编辑' data-testid='credential-edit' onClick={() => setEditing(c)}><Pencil className='size-4' /></Button>
                  <Button variant='ghost' size='icon' title='移除' data-testid='credential-remove' onClick={() => setDeleting(c)}><Trash2 className='size-4' /></Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Page>
      <CredentialDialog credential={editing === 'new' ? null : editing} open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} />
      <AlertDialog open={deleting !== null} onOpenChange={(o) => !o && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>移除「{deleting?.label}」？</AlertDialogTitle>
            <AlertDialogDescription>
              {deleting?.source === 'config' ? '会从 providers.json 里拿掉这一家，' : '会取消选中这一家，'}存下的 key 一并删除；它的模型从模型列表里消失。
              {deleting?.keySource === 'env' && ` 环境变量 ${deleting.keyEnv} 里的 key 删不掉，要自己去掉。`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction className='bg-destructive text-white hover:bg-destructive/90' onClick={() => { if (deleting) remove.mutate(deleting.id); setDeleting(null) }}>移除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
