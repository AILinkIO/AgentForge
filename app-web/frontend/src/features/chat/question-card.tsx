import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Check, MessageCircleQuestion } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import type { QuestionRequest } from '@/lib/types'
import { cn } from '@/lib/utils'

/** clarify 摆出来的选择题：每题单选 / 多选，外加「自己填」。 */
export function QuestionCard({ session, req }: { session: string; req: QuestionRequest }) {
  const [picked, setPicked] = useState<string[][]>(() => req.questions.map(() => []))
  const [other, setOther] = useState<string[]>(() => req.questions.map(() => ''))
  const [tab, setTab] = useState(0)
  const send = useMutation({
    mutationFn: (answers: string[][] | null) => api.answer(session, answers),
    onError: (e: Error) => toast.error(e.message),
  })
  const toggle = (qi: number, label: string, multiple: boolean) =>
    setPicked((p) => p.map((xs, i) => i !== qi ? xs : multiple ? (xs.includes(label) ? xs.filter((x) => x !== label) : [...xs, label]) : [label]))
  const answers = () => picked.map((xs, i) => other[i].trim() ? [...xs.filter(() => req.questions[i].multiple), other[i].trim()] : xs)
  const q = req.questions[tab]
  const ready = answers().every((a) => a.length > 0)

  return (
    <div data-testid='question-card' className='overflow-hidden rounded-xl border-2 border-info/40 bg-card shadow-sm'>
      <div className='flex items-center gap-2 bg-info-soft px-4 py-2.5 text-sm font-medium text-info'>
        <MessageCircleQuestion className='size-4' />它想问你
        {req.questions.length > 1 && (
          <div className='ms-auto flex gap-1'>
            {req.questions.map((x, i) => (
              <button key={i} type='button' onClick={() => setTab(i)}
                className={cn('rounded-md px-2 py-0.5 text-xs', i === tab ? 'bg-background text-foreground shadow-sm' : 'opacity-75 hover:opacity-100')}>
                {picked[i].length > 0 || other[i] ? <Check className='me-1 inline size-3' /> : null}{x.header || `第 ${i + 1} 题`}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className='space-y-3 px-4 py-3'>
        <div className='text-sm font-medium'>{q.question}</div>
        <div className='grid gap-2 sm:grid-cols-2'>
          {q.options.map((o) => {
            const on = picked[tab].includes(o.label)
            return (
              <button key={o.label} type='button' onClick={() => toggle(tab, o.label, q.multiple)}
                className={cn('rounded-lg border px-3 py-2 text-start transition-colors hover:bg-muted/60', on && 'border-primary bg-primary-soft ring-1 ring-primary')}>
                <div className='flex items-center gap-2 text-sm font-medium'>
                  <span className={cn('flex size-4 shrink-0 items-center justify-center border', q.multiple ? 'rounded' : 'rounded-full', on && 'border-primary bg-primary text-primary-foreground')}>
                    {on && <Check className='size-3' />}
                  </span>
                  {o.label}
                </div>
                {o.description && <div className='mt-0.5 ps-6 text-xs text-muted-foreground'>{o.description}</div>}
              </button>
            )
          })}
        </div>
        <Input placeholder='都不合适？自己填' value={other[tab]} onChange={(e) => setOther((o) => o.map((x, i) => i === tab ? e.target.value : x))} />
        <div className='flex gap-2'>
          {tab < req.questions.length - 1
            ? <Button size='sm' onClick={() => setTab(tab + 1)}>下一题</Button>
            : <Button size='sm' disabled={!ready || send.isPending} onClick={() => send.mutate(answers())}>提交回答</Button>}
          <Button size='sm' variant='ghost' disabled={send.isPending} onClick={() => send.mutate(null)}>先不答</Button>
        </div>
      </div>
    </div>
  )
}
