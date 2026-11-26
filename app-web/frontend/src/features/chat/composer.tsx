import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUp, Brain, ImagePlus, Paperclip, Square, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { api, qk } from '@/lib/api'
import type { Snapshot } from '@/lib/types'
import { cn } from '@/lib/utils'

const THINK_LABEL: Record<string, string> = { off: '不思考', low: '少想', medium: '适中', high: '多想', max: '最多', minimal: '极少' }

/** 输入区：Enter 发送、Shift+Enter 换行；忙时发出去的话排队（下一轮开头并进去）；粘贴图片当附件。 */
export function Composer({ snap }: { snap: Snapshot }) {
  const id = snap.session.id
  const qc = useQueryClient()
  const [text, setText] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const { data: models } = useQuery({ queryKey: qk.models, queryFn: api.models })
  const { data: info } = useQuery({ queryKey: qk.info, queryFn: api.info })
  const busy = snap.session.busy

  // 出错 / 按停时排着的话还回草稿
  useEffect(() => { if (snap.draft && !text) setText(snap.draft) }, [snap.draft]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { ref.current?.focus() }, [id])
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(240, Math.max(44, el.scrollHeight))}px`
  }, [text])

  const send = useMutation({
    mutationFn: (t: string) => api.send(id, t),
    onSuccess: (r) => {
      setText('')
      if (r.result === 'QUEUED') toast('已排队：这一轮做完前会并进去')
      qc.invalidateQueries({ queryKey: qk.snapshot(id) })
    },
    onError: (e: Error) => toast.error(e.message),
  })
  const stop = useMutation({ mutationFn: () => api.interrupt(id), onError: (e: Error) => toast.error(e.message) })
  const settings = useMutation({
    mutationFn: (s: { model?: string; thinking?: string; agent?: string }) => api.settings(id, s),
    onSuccess: (snap) => qc.setQueryData(qk.snapshot(id), snap),
    onError: (e: Error) => toast.error(e.message),
  })

  const addImage = async (f: File) => {
    if (!f.type.startsWith('image/')) return toast.error('只收图片')
    const data = await new Promise<string>((res, rej) => {
      const r = new FileReader()
      r.onload = () => res(String(r.result).split(',')[1] ?? '')
      r.onerror = () => rej(r.error)
      r.readAsDataURL(f)
    })
    try {
      await api.attach(id, f.name || 'image', f.type, data)
      qc.invalidateQueries({ queryKey: qk.snapshot(id) })
    } catch (e) { toast.error((e as Error).message) }
  }

  const submit = () => {
    const t = text.trim()
    if (t && !send.isPending) send.mutate(t)
  }

  const byProvider = new Map<string, string[]>()
  for (const m of models?.models ?? []) byProvider.set(m.provider, [...(byProvider.get(m.provider) ?? []), m.ref])
  // 模型表还没到（或当前模型不在表里）时也要显示出当前那个，不然选择框是空的
  const cur = snap.session.model
  if (cur && ![...byProvider.values()].some((rs) => rs.includes(cur))) {
    const p = cur.split('/')[0]
    byProvider.set(p, [...(byProvider.get(p) ?? []), cur])
  }
  const levels = snap.thinkingLevels ?? []
  const mainAgents = (info?.agents ?? []).filter((a) => !a.subagent)

  return (
    <div className='rounded-2xl border bg-card shadow-sm transition-shadow focus-within:shadow-md focus-within:ring-1 focus-within:ring-ring/50'>
      {snap.attachments.length > 0 && (
        <div className='flex flex-wrap items-center gap-1.5 border-b px-3 py-2' data-testid='attachments'>
          {snap.attachments.map((a, i) => (
            <span key={i} className='flex items-center gap-1 rounded-md bg-muted px-2 py-0.5 text-xs'><Paperclip className='size-3' />{a}</span>
          ))}
          <button type='button' className='ms-1 text-xs text-muted-foreground hover:text-foreground'
            onClick={async () => { await api.clearAttachments(id); qc.invalidateQueries({ queryKey: qk.snapshot(id) }) }}>
            <X className='inline size-3' />清掉
          </button>
        </div>
      )}
      {snap.queue.length > 0 && (
        <div className='border-b px-3 py-2 text-xs text-muted-foreground'>
          排队中：{snap.queue.map((q, i) => <span key={i} className='me-2 rounded bg-muted px-1.5 py-0.5'>{q.length > 40 ? q.slice(0, 40) + '…' : q}</span>)}
        </div>
      )}
      <textarea ref={ref} rows={1} value={text} data-testid='composer-input' onChange={(e) => setText(e.target.value)}
        placeholder={busy ? '它在忙；现在发的话会排队，下一轮开头并进去' : '让它做点什么…（Enter 发送，Shift+Enter 换行）'}
        className='block max-h-60 min-h-11 w-full resize-none bg-transparent px-4 pt-3 pb-1 text-sm leading-relaxed outline-none placeholder:text-muted-foreground'
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submit() }
          if (e.key === 'Escape' && busy) { e.preventDefault(); stop.mutate() }
        }}
        onPaste={(e) => {
          for (const it of e.clipboardData.items) {
            if (it.kind === 'file') { const f = it.getAsFile(); if (f) { e.preventDefault(); addImage(f) } }
          }
        }} />
      <div className='flex items-center gap-1.5 px-2 pb-2'>
        <input ref={fileRef} type='file' accept='image/*' hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) addImage(f); e.target.value = '' }} />
        <Button variant='ghost' size='icon' className='size-8 text-muted-foreground' title='附图片（也可直接粘贴）' onClick={() => fileRef.current?.click()}>
          <ImagePlus className='size-4' />
        </Button>
        <Select value={snap.session.model ?? ''} onValueChange={(model) => settings.mutate({ model })}>
          <SelectTrigger size='sm' className='h-8 max-w-56 border-0 bg-transparent font-mono text-xs shadow-none hover:bg-muted'>
            <SelectValue placeholder='选模型' />
          </SelectTrigger>
          <SelectContent>
            {[...byProvider.entries()].map(([p, refs]) => (
              <SelectGroup key={p}>
                <SelectLabel>{p}</SelectLabel>
                {refs.map((r) => <SelectItem key={r} value={r} className='font-mono text-xs'>{r.slice(p.length + 1)}</SelectItem>)}
              </SelectGroup>
            ))}
          </SelectContent>
        </Select>
        {levels.length > 0 && (
          <Select value={snap.thinking ?? 'off'} onValueChange={(thinking) => settings.mutate({ thinking })}>
            <SelectTrigger size='sm' className='h-8 border-0 bg-transparent text-xs shadow-none hover:bg-muted'>
              <Brain className='size-3.5' /><SelectValue />
            </SelectTrigger>
            <SelectContent>
              {levels.map((l) => <SelectItem key={l} value={l} className='text-xs'>{THINK_LABEL[l] ?? l}</SelectItem>)}
            </SelectContent>
          </Select>
        )}
        {mainAgents.length > 1 && (
          <Select value={snap.session.agent ?? 'main'} onValueChange={(agent) => settings.mutate({ agent })} disabled={busy}>
            <SelectTrigger size='sm' className='hidden h-8 border-0 bg-transparent text-xs shadow-none hover:bg-muted sm:flex'><SelectValue /></SelectTrigger>
            <SelectContent>
              {mainAgents.map((a) => <SelectItem key={a.name} value={a.name} className='text-xs'>{a.name}</SelectItem>)}
            </SelectContent>
          </Select>
        )}
        <div className='ms-auto flex items-center gap-1.5'>
          {busy && (
            <Button size='sm' variant='outline' className='h-8 rounded-full' onClick={() => stop.mutate()} title='停下（Esc）' data-testid='stop'>
              <Square className='size-3 fill-current' />停下
            </Button>
          )}
          <Button size='icon' className={cn('size-8 rounded-full', !text.trim() && 'opacity-40')} disabled={!text.trim() || send.isPending} onClick={submit} title='发送' data-testid='send'>
            <ArrowUp className='size-4' />
          </Button>
        </div>
      </div>
    </div>
  )
}
