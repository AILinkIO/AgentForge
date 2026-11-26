import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowUp, ChevronRight, Folder, FolderGit2, FolderPlus, Home, Loader2, Plus } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import { cn } from '@/lib/utils'

/** 路径拆成可点的面包屑：/home/me/code → [/, home, me, code]。 */
function crumbs(path: string): { label: string; path: string }[] {
  const parts = path.split('/').filter(Boolean)
  const out = [{ label: '/', path: '/' }]
  let acc = ''
  for (const p of parts) { acc += `/${p}`; out.push({ label: p, path: acc }) }
  return out
}

/**
 * 服务器目录浏览（内嵌在项目对话框里，不再叠一层对话框）：面包屑、敲路径、逐级点进去、新建文件夹，
 * 「添加这个文件夹」把当前目录交给 {@code onPick}。
 */
export function FolderBrowser({ onPick, start, chosen }: { onPick: (path: string) => void; start?: string; chosen: string[] }) {
  const qc = useQueryClient()
  const [path, setPath] = useState<string | undefined>(start)
  const [typed, setTyped] = useState(start ?? '')
  const [hidden, setHidden] = useState(false)
  const [newName, setNewName] = useState<string | null>(null)
  const { data, error, isFetching } = useQuery({ queryKey: ['fs', path ?? '~', hidden], queryFn: () => api.fs(path, hidden), retry: false })
  useEffect(() => { if (data) setTyped(data.path) }, [data])

  const mkdir = async () => {
    if (!data || !newName?.trim()) return
    try {
      const r = await api.mkdir(data.path, newName.trim())
      qc.setQueryData(['fs', r.path, hidden], r)
      setPath(r.path)
      setNewName(null)
    } catch (e) { toast.error((e as Error).message) }
  }
  const already = data ? chosen.includes(data.path) : false

  return (
    <div className='overflow-hidden rounded-lg border' data-testid='folder-browser'>
      <form className='flex items-center gap-1.5 border-b bg-muted/30 p-2' onSubmit={(e) => { e.preventDefault(); setPath(typed.trim() || undefined) }}>
        <Button type='button' variant='ghost' size='icon' className='size-8 shrink-0' title='家目录' onClick={() => setPath(data?.home)}><Home className='size-4' /></Button>
        <Button type='button' variant='ghost' size='icon' className='size-8 shrink-0' title='上一级' disabled={!data?.parent} onClick={() => setPath(data?.parent)}><ArrowUp className='size-4' /></Button>
        <Input value={typed} onChange={(e) => setTyped(e.target.value)} className='h-8 bg-background font-mono text-xs' placeholder='/path/to/project 或 ~/code' data-testid='folder-path' />
        <Button type='submit' variant='secondary' size='sm' className='shrink-0'>前往</Button>
      </form>
      {data && (
        <div className='flex flex-wrap items-center gap-0.5 border-b px-2 py-1.5 text-xs' data-testid='folder-crumbs'>
          {crumbs(data.path).map((c, i, all) => (
            <span key={c.path} className='flex items-center'>
              {i > 0 && i < all.length && <ChevronRight className='size-3 text-muted-foreground/60' />}
              <button type='button' onClick={() => setPath(c.path)}
                className={cn('rounded px-1.5 py-0.5 font-mono hover:bg-muted', i === all.length - 1 ? 'font-semibold text-foreground' : 'text-muted-foreground')}>
                {c.label}
              </button>
            </span>
          ))}
          {data.git && <span className='ms-1 rounded bg-primary-soft px-1.5 text-[10px] text-primary'>git</span>}
        </div>
      )}
      {error && <div className='bg-destructive-soft px-3 py-2 text-xs text-destructive'>{(error as Error).message}</div>}
      <div className='h-56 overflow-y-auto'>
        {isFetching && !data ? <div className='flex h-full items-center justify-center'><Loader2 className='size-4 animate-spin text-muted-foreground' /></div> : (
          <ul>
            {newName !== null && (
              <li className='flex items-center gap-2 border-b px-3 py-1.5'>
                <FolderPlus className='size-4 shrink-0 text-primary' />
                <Input autoFocus value={newName} onChange={(e) => setNewName(e.target.value)} className='h-8' placeholder='新文件夹的名字'
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); mkdir() } if (e.key === 'Escape') { e.stopPropagation(); setNewName(null) } }} />
                <Button size='sm' onClick={mkdir}>建立</Button>
              </li>
            )}
            {data?.dirs.map((d) => (
              <li key={d.path}>
                <button type='button' onClick={() => setPath(d.path)} data-testid='folder-entry'
                  className='group flex w-full items-center gap-2 px-3 py-1.5 text-start text-sm hover:bg-muted'>
                  {d.git ? <FolderGit2 className='size-4 shrink-0 text-primary' /> : <Folder className='size-4 shrink-0 text-muted-foreground' />}
                  <span className='truncate'>{d.name}</span>
                  {d.git && <span className='rounded bg-muted px-1.5 text-[10px] text-muted-foreground'>git</span>}
                  <ChevronRight className='ms-auto size-3.5 text-muted-foreground opacity-0 group-hover:opacity-100' />
                </button>
              </li>
            ))}
            {data && data.dirs.length === 0 && newName === null && <li className='px-3 py-8 text-center text-xs text-muted-foreground'>这里没有子文件夹</li>}
            {data?.truncated && <li className='px-3 py-2 text-center text-xs text-muted-foreground'>太多了，只列出前 1000 个；可以直接敲路径</li>}
          </ul>
        )}
      </div>
      <div className='flex items-center gap-2 border-t bg-muted/30 px-2 py-2'>
        <label className='flex items-center gap-2 px-1 text-xs text-muted-foreground'><Checkbox checked={hidden} onCheckedChange={(v) => setHidden(v === true)} />隐藏文件夹</label>
        <Button variant='ghost' size='sm' onClick={() => setNewName('')} disabled={!data}><FolderPlus className='size-3.5' />新建</Button>
        <Button size='sm' className='ms-auto' disabled={!data || already} onClick={() => data && onPick(data.path)} data-testid='folder-add-current'>
          <Plus className='size-3.5' />{already ? '已添加' : '添加这个文件夹'}
        </Button>
      </div>
    </div>
  )
}
