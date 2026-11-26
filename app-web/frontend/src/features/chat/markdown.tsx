import { memo, type ReactNode, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import remarkGfm from 'remark-gfm'
import { Check, Copy } from 'lucide-react'

/** 助手回复的 Markdown（GFM + highlight.js 高亮）；代码块带语言名与复制按钮。 */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  return (
    <div className='chat-md'>
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeHighlight, { detect: false, ignoreMissing: true }]]}
        components={{
          pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
          a: ({ children, href }) => <a href={href} target='_blank' rel='noopener noreferrer'>{children}</a>,
        }}>
        {text}
      </ReactMarkdown>
    </div>
  )
})

function CodeBlock({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLPreElement>(null)
  const [copied, setCopied] = useState(false)
  const code = (children as { props?: { className?: string } } | undefined)?.props
  const lang = /language-([\w+-]+)/.exec(code?.className ?? '')?.[1]
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(ref.current?.innerText ?? '')
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch { /* 剪贴板不可用（非 https）时忽略 */ }
  }
  return (
    <div className='code-block group/code'>
      <div className='absolute end-1.5 top-1.5 flex items-center gap-1.5 opacity-0 transition-opacity group-hover/code:opacity-100'>
        {lang && <span className='text-[10px] tracking-wide text-muted-foreground uppercase'>{lang}</span>}
        <button type='button' onClick={copy}
          className='flex items-center gap-1 rounded-md border bg-background px-2 py-0.5 text-[11px] text-muted-foreground hover:text-foreground'>
          {copied ? <Check className='size-3' /> : <Copy className='size-3' />}{copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre ref={ref}>{children}</pre>
    </div>
  )
}

/** unified diff：按 +/-/@@ 上色。 */
export function DiffView({ text, className }: { text: string; className?: string }) {
  return (
    <pre className={`diff-view overflow-auto ${className ?? ''}`}>
      {text.split('\n').map((l, i) => {
        const c = l.startsWith('+++') || l.startsWith('---') ? 'meta'
          : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : l.startsWith('@@') ? 'hunk' : ''
        return <div key={i} className={`${c} px-3`}>{l || ' '}</div>
      })}
    </pre>
  )
}
