import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react'

export type Mode = 'light' | 'dark' | 'system'
export type Preset = 'neutral' | 'blue' | 'green' | 'orange' | 'rose' | 'violet'
export type Scale = 'sm' | 'md' | 'lg'
export type SidebarVariant = 'inset' | 'sidebar' | 'floating'
export type ContentWidth = 'full' | 'centered'

export interface UiSettings {
  mode: Mode
  preset: Preset
  radius: number
  scale: Scale
  sidebar: SidebarVariant
  content: ContentWidth
}

const DEFAULTS: UiSettings = { mode: 'system', preset: 'neutral', radius: 0.625, scale: 'md', sidebar: 'inset', content: 'full' }
const KEY = 'agentforge-ui'

interface Ctx extends UiSettings {
  theme: 'light' | 'dark'
  set: (patch: Partial<UiSettings>) => void
  reset: () => void
}

const ThemeContext = createContext<Ctx | null>(null)

function load(): UiSettings {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') } } catch { return DEFAULTS }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [s, setS] = useState<UiSettings>(load)
  const [sysDark, setSysDark] = useState(() => matchMedia('(prefers-color-scheme: dark)').matches)

  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const on = () => setSysDark(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])

  const theme: 'light' | 'dark' = s.mode === 'system' ? (sysDark ? 'dark' : 'light') : s.mode

  useEffect(() => {
    const h = document.documentElement
    h.classList.toggle('dark', theme === 'dark')
    h.dataset.preset = s.preset
    h.dataset.scale = s.scale
    h.style.setProperty('--radius', `${s.radius}rem`)
    localStorage.setItem(KEY, JSON.stringify(s))
  }, [s, theme])

  const value = useMemo<Ctx>(() => ({
    ...s,
    theme,
    set: (p) => setS((o) => ({ ...o, ...p })),
    reset: () => setS(DEFAULTS),
  }), [s, theme])

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): Ctx {
  const c = useContext(ThemeContext)
  if (!c) throw new Error('useTheme 要在 ThemeProvider 里用')
  return c
}
