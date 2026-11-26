import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, FileJson, Plus, Search, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { VendorMark } from '@/components/common/vendor-mark'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { api, qk } from '@/lib/api'
import type { ConfigModel, Credential } from '@/lib/types'
import { cn } from '@/lib/utils'

function Field({ label, hint, children, className }: { label: string; hint?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label>{label}</Label>
      {children}
      {hint && <p className='text-xs text-muted-foreground'>{hint}</p>}
    </div>
  )
}

type Row = { id: string; context: string; maxOutput: string }
const toRows = (ms?: ConfigModel[]): Row[] =>
  ms?.length ? ms.map((m) => ({ id: m.id, context: m.context ? String(m.context) : '', maxOutput: m.maxOutput ? String(m.maxOutput) : '' })) : [{ id: '', context: '', maxOutput: '' }]

/**
 * 添加 / 编辑凭证（照 LiteGate）：「从 models.dev 选择」一键加一家（模型跟着目录走），或「手动填写」写进 providers.json
 * （自建网关、内网服务；模型逐条列出）。key 加密存进配置目录，之后只显示末 4 位。
 */
export function CredentialDialog({ credential, open, onOpenChange }: {
  credential: Credential | null
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const qc = useQueryClient()
  const isNew = credential === null
  const [mode, setMode] = useState<'catalog' | 'config'>('catalog')
  const [q, setQ] = useState('')
  const [provider, setProvider] = useState<string | null>(null)
  const [id, setId] = useState('')
  const [label, setLabel] = useState('')
  const [apiKind, setApiKind] = useState('anthropic')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKeyEnv, setApiKeyEnv] = useState('')
  const [key, setKey] = useState('')
  const [rows, setRows] = useState<Row[]>(toRows())
  const { data: providers } = useQuery({ queryKey: ['catalog-providers'], queryFn: api.catalogProviders, enabled: open && isNew })

  useEffect(() => {
    if (!open) return
    setMode(credential ? credential.source : 'catalog')
    setQ('')
    setProvider(null)
    setId(credential?.id ?? '')
    setLabel(credential?.source === 'config' ? credential.label : '')
    setApiKind(credential?.api ?? 'anthropic')
    setBaseUrl(credential?.source === 'config' ? credential.baseUrl ?? '' : '')
    setApiKeyEnv(credential?.apiKeyEnv ?? '')
    setKey('')
    setRows(toRows(credential?.configModels))
  }, [open, credential])

  const usable = useMemo(() => (providers ?? []).filter((p) => p.formatKnown && !p.enabled)
    .filter((p) => !q || p.id.includes(q.toLowerCase()) || p.label.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => b.models - a.models), [providers, q])
  const picked = providers?.find((p) => p.id === provider)

  const models = rows.filter((r) => r.id.trim()).map((r) => ({
    id: r.id.trim(), context: Number(r.context) || undefined, maxOutput: Number(r.maxOutput) || undefined,
  }))

  const save = useMutation({
    mutationFn: async () => {
      if (isNew && mode === 'catalog') return api.addCredential({ source: 'catalog', provider: provider!, baseUrl: baseUrl || undefined, key: key || undefined })
      const body = { source: 'config' as const, id: id.trim(), label, api: apiKind, baseUrl, apiKeyEnv, key: key || undefined, models }
      if (isNew) return api.addCredential(body)
      if (credential!.source === 'catalog') return api.updateCredential(credential!.id, { baseUrl: baseUrl || undefined, key: key || undefined })
      return api.updateCredential(credential!.id, body)
    },
    onSuccess: (list) => {
      qc.setQueryData(qk.credentials, list)
      for (const k of [qk.models, qk.info, ['catalog-providers']]) qc.invalidateQueries({ queryKey: k })
      toast.success('已保存')
      onOpenChange(false)
    },
    onError: (e: Error) => toast.error(e.message),
  })

  const valid = isNew && mode === 'catalog'
    ? provider != null && (!picked?.needsEndpoint || baseUrl.trim() !== '')
    : credential?.source === 'catalog' ? true
      : id.trim() !== '' && baseUrl.trim() !== '' && models.length > 0

  const keyHint = credential
    ? credential.keySource === 'env' ? `现在用的是环境变量 ${credential.keyEnv}（优先于这里存的）；留空不改`
      : credential.keyHint ? `现在：${credential.keyHint}；留空不改` : '还没有 key'
    : picked ? `加密存进配置目录，之后只显示末 4 位；也可以设环境变量 ${picked.envNames[0] ?? ''}` : '加密存进配置目录，之后只显示末 4 位'

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[90svh] gap-5 overflow-y-auto sm:max-w-2xl' data-testid='credential-dialog'>
        <DialogHeader>
          <DialogTitle>{isNew ? '添加凭证' : `编辑凭证：${credential.label}`}</DialogTitle>
          <DialogDescription>
            {isNew ? '模型来自两处：models.dev 目录里的厂商（模型跟着目录同步），或自己写进 providers.json 的服务（自建网关、内网）。'
              : credential.source === 'catalog' ? '这家来自 models.dev，模型跟着目录走；这里能换 key 或改端点。' : '这家写在 providers.json 里，保存会改写那个文件。'}
          </DialogDescription>
        </DialogHeader>
        {isNew && (
          <Tabs value={mode} onValueChange={(v) => setMode(v as 'catalog' | 'config')}>
            <TabsList>
              <TabsTrigger value='catalog'>从 models.dev 选择</TabsTrigger>
              <TabsTrigger value='config'>手动填写</TabsTrigger>
            </TabsList>
          </Tabs>
        )}

        {isNew && mode === 'catalog' ? (
          <div className='grid gap-4'>
            <div className='overflow-hidden rounded-lg border'>
              <div className='flex items-center gap-2 border-b px-3'>
                <Search className='size-4 text-muted-foreground' />
                <input value={q} onChange={(e) => setQ(e.target.value)} placeholder='搜索厂商（id 或名字）' data-testid='catalog-search'
                  className='h-10 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground' />
              </div>
              <ul className='max-h-64 overflow-y-auto'>
                {usable.map((p) => (
                  <li key={p.id}>
                    <button type='button' onClick={() => setProvider(p.id)} data-testid='catalog-provider'
                      className={cn('flex w-full items-center gap-3 px-3 py-2 text-start hover:bg-muted', provider === p.id && 'bg-primary-soft')}>
                      <VendorMark id={p.id} label={p.label} className='size-7 text-xs' />
                      <div className='min-w-0 flex-1'>
                        <div className='truncate text-sm font-medium'>{p.label}</div>
                        <div className='truncate font-mono text-[11px] text-muted-foreground'>{p.id}{p.api ? ` · ${p.api}` : ''}</div>
                      </div>
                      <span className='shrink-0 text-xs text-muted-foreground'>{p.models} 个模型</span>
                      {provider === p.id && <Check className='size-4 shrink-0 text-primary' />}
                    </button>
                  </li>
                ))}
                {providers && usable.length === 0 && <li className='px-3 py-6 text-center text-xs text-muted-foreground'>没有匹配的厂商（已经加过的不再列出）</li>}
                {!providers && <li className='px-3 py-6 text-center text-xs text-muted-foreground'>载入中…</li>}
              </ul>
            </div>
            {picked?.needsEndpoint && (
              <Field label='Base URL' hint='models.dev 没给这家的端点，要自己填'>
                <Input className='font-mono text-xs' value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder='https://…' />
              </Field>
            )}
            <Field label='API Key' hint={keyHint}>
              <Input type='password' autoComplete='off' value={key} onChange={(e) => setKey(e.target.value)} data-testid='credential-key' />
            </Field>
          </div>
        ) : credential?.source === 'catalog' ? (
          <div className='grid gap-4'>
            <Field label='Base URL（可选）' hint={`留空 = 用目录 / 内置的端点（现在：${credential.baseUrl ?? '—'}）`}>
              <Input className='font-mono text-xs' value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder={credential.baseUrl ?? ''} />
            </Field>
            <Field label='API Key' hint={keyHint}>
              <Input type='password' autoComplete='off' value={key} onChange={(e) => setKey(e.target.value)} data-testid='credential-key' />
            </Field>
          </div>
        ) : (
          <div className='grid gap-4'>
            <div className='grid gap-4 sm:grid-cols-2'>
              <Field label='ID' hint='模型 ref 的前缀，比如 litegate/…'>
                <Input value={id} onChange={(e) => setId(e.target.value)} disabled={!isNew} placeholder='litegate' className='font-mono text-xs' data-testid='credential-id' />
              </Field>
              <Field label='显示名'>
                <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder='LiteGate' data-testid='credential-label' />
              </Field>
              <Field label='协议'>
                <Select value={apiKind} onValueChange={setApiKind}>
                  <SelectTrigger className='w-full'><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value='anthropic'>Anthropic（/v1/messages）</SelectItem>
                    <SelectItem value='openai'>OpenAI 兼容（/chat/completions）</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
              <Field label='Base URL'>
                <Input className='font-mono text-xs' value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} data-testid='credential-base'
                  placeholder={apiKind === 'openai' ? 'https://api.example.com/v1' : 'http://127.0.0.1:4000'} />
              </Field>
              <Field label='API Key' hint={keyHint}>
                <Input type='password' autoComplete='off' value={key} onChange={(e) => setKey(e.target.value)} data-testid='credential-key' />
              </Field>
              <Field label='Key 的环境变量（可选）' hint={`缺省 ${(id || 'ID').toUpperCase().replace(/[^A-Z0-9]/g, '_')}_API_KEY`}>
                <Input className='font-mono text-xs' value={apiKeyEnv} onChange={(e) => setApiKeyEnv(e.target.value)} />
              </Field>
            </div>
            <fieldset className='space-y-2 border-t pt-4'>
              <legend className='-ms-0.5 pe-2 text-xs font-semibold tracking-wide text-muted-foreground'>模型</legend>
              <div className='grid grid-cols-[1fr_7rem_7rem_2rem] gap-2 text-xs text-muted-foreground'>
                <span>模型 id</span><span>上下文窗口</span><span>最大输出</span><span />
              </div>
              {rows.map((r, i) => (
                <div key={i} className='grid grid-cols-[1fr_7rem_7rem_2rem] gap-2'>
                  <Input value={r.id} onChange={(e) => setRows(rows.map((x, j) => j === i ? { ...x, id: e.target.value } : x))}
                    className='font-mono text-xs' placeholder='MiniMax-M3.1-Flash' data-testid='credential-model' />
                  <Input value={r.context} inputMode='numeric' onChange={(e) => setRows(rows.map((x, j) => j === i ? { ...x, context: e.target.value } : x))} placeholder='200000' />
                  <Input value={r.maxOutput} inputMode='numeric' onChange={(e) => setRows(rows.map((x, j) => j === i ? { ...x, maxOutput: e.target.value } : x))} placeholder='32000' />
                  <Button variant='ghost' size='icon' className='size-9 text-muted-foreground' disabled={rows.length === 1}
                    onClick={() => setRows(rows.filter((_, j) => j !== i))}><Trash2 className='size-4' /></Button>
                </div>
              ))}
              <Button variant='outline' size='sm' onClick={() => setRows([...rows, { id: '', context: '', maxOutput: '' }])}><Plus className='size-3.5' />再加一个模型</Button>
            </fieldset>
            <p className='flex items-center gap-1.5 text-xs text-muted-foreground'><FileJson className='size-3.5' />保存会写进配置目录的 providers.json（文件里的其他内容不动）</p>
          </div>
        )}
        <DialogFooter>
          {isNew && mode === 'catalog' && picked && <Badge variant='muted' className='me-auto'>{picked.models} 个目录模型</Badge>}
          <Button variant='ghost' onClick={() => onOpenChange(false)}>取消</Button>
          <Button disabled={!valid || save.isPending} onClick={() => save.mutate()} data-testid='credential-save'>保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
