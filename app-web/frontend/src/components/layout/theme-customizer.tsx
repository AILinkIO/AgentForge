import { Check, RotateCcw, Settings2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { type ContentWidth, type Mode, type Preset, type Scale, type SidebarVariant, useTheme } from '@/context/theme-provider'
import { cn } from '@/lib/utils'

const PRESETS: { id: Preset; label: string; swatch: string }[] = [
  { id: 'neutral', label: '默认', swatch: 'oklch(0.205 0 0)' },
  { id: 'blue', label: '湖蓝', swatch: 'oklch(0.546 0.245 262.881)' },
  { id: 'green', label: '森绿', swatch: 'oklch(0.596 0.145 163.225)' },
  { id: 'orange', label: '日落', swatch: 'oklch(0.646 0.222 41.116)' },
  { id: 'rose', label: '玫瑰', swatch: 'oklch(0.586 0.253 17.585)' },
  { id: 'violet', label: '薰衣草', swatch: 'oklch(0.541 0.281 293.009)' },
]

function Seg<T extends string | number>({ value, options, onChange }: { value: T; options: { v: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className='grid gap-2' style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}>
      {options.map((o) => (
        <Button key={String(o.v)} type='button' variant='outline' size='sm' onClick={() => onChange(o.v)}
          className={cn('font-normal', value === o.v && 'border-primary ring-1 ring-primary')}>
          {o.label}
        </Button>
      ))}
    </div>
  )
}

/** 主题定制面板（照 shadcnuikit 的 Theme Customizer：配色预设、圆角、缩放、明暗、侧栏样式、内容宽度）。 */
export function ThemeCustomizer() {
  const t = useTheme()
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant='ghost' size='icon' className='size-8' title='界面设置'><Settings2 className='size-4' /></Button>
      </SheetTrigger>
      <SheetContent className='w-80 gap-0 overflow-y-auto sm:max-w-80'>
        <SheetHeader>
          <SheetTitle>界面设置</SheetTitle>
          <SheetDescription>只存在这个浏览器里</SheetDescription>
        </SheetHeader>
        <div className='flex flex-col gap-6 px-4 pb-6'>
          <div className='space-y-2'>
            <Label>配色</Label>
            <div className='grid grid-cols-3 gap-2'>
              {PRESETS.map((p) => (
                <button key={p.id} type='button' onClick={() => t.set({ preset: p.id })}
                  className={cn('flex items-center gap-2 rounded-md border px-2.5 py-2 text-xs transition-colors hover:bg-accent',
                    t.preset === p.id && 'border-primary ring-1 ring-primary')}>
                  <span className='flex size-4 items-center justify-center rounded-full' style={{ background: p.swatch }}>
                    {t.preset === p.id && <Check className='size-2.5 text-white' />}
                  </span>
                  {p.label}
                </button>
              ))}
            </div>
          </div>
          <div className='space-y-2'>
            <Label>明暗</Label>
            <Seg<Mode> value={t.mode} onChange={(mode) => t.set({ mode })}
              options={[{ v: 'light', label: '浅色' }, { v: 'dark', label: '深色' }, { v: 'system', label: '跟随系统' }]} />
          </div>
          <div className='space-y-2'>
            <Label>圆角</Label>
            <Seg<number> value={t.radius} onChange={(radius) => t.set({ radius })}
              options={[{ v: 0, label: '0' }, { v: 0.3, label: '0.3' }, { v: 0.5, label: '0.5' }, { v: 0.625, label: '0.625' }, { v: 0.875, label: '0.875' }]} />
          </div>
          <div className='space-y-2'>
            <Label>缩放</Label>
            <Seg<Scale> value={t.scale} onChange={(scale) => t.set({ scale })}
              options={[{ v: 'sm', label: '紧凑' }, { v: 'md', label: '默认' }, { v: 'lg', label: '宽松' }]} />
          </div>
          <div className='space-y-2'>
            <Label>侧栏</Label>
            <Seg<SidebarVariant> value={t.sidebar} onChange={(sidebar) => t.set({ sidebar })}
              options={[{ v: 'inset', label: '内嵌' }, { v: 'sidebar', label: '贴边' }, { v: 'floating', label: '浮起' }]} />
          </div>
          <div className='space-y-2'>
            <Label>内容宽度</Label>
            <Seg<ContentWidth> value={t.content} onChange={(content) => t.set({ content })}
              options={[{ v: 'full', label: '铺满' }, { v: 'centered', label: '居中' }]} />
          </div>
          <Button variant='outline' onClick={t.reset}><RotateCcw className='size-4' />恢复默认</Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
