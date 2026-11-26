import { cn } from '@/lib/utils'

const KNOWN: Record<string, string> = {
  anthropic: '#d97757', openai: '#10a37f', deepseek: '#4d6bfe', minimax: '#e2457a', google: '#4285f4', moonshot: '#16191e',
  zhipu: '#3859ff', qwen: '#615ced', xai: '#111111', mistral: '#fa520f', openrouter: '#6467f2', litegate: '#4f46e5',
}

/** provider 的标记：首字母色块（常见几家用品牌色，其余按 id 算一个色相）。 */
export function VendorMark({ id, label, className }: { id: string; label?: string; className?: string }) {
  const key = Object.keys(KNOWN).find((k) => id.toLowerCase().includes(k))
  let hue = 0
  for (const ch of id) hue = (hue * 31 + ch.charCodeAt(0)) % 360
  const bg = key ? KNOWN[key] : `oklch(0.6 0.13 ${hue})`
  const letter = (label || id).replace(/[^\p{L}\p{N}]/gu, '').slice(0, 1).toUpperCase() || '?'
  return (
    <span className={cn('flex size-9 shrink-0 items-center justify-center rounded-lg text-sm font-semibold text-white shadow-xs', className)}
      style={{ background: bg }}>
      {letter}
    </span>
  )
}
