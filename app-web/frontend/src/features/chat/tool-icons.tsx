import {
  Bot, BookOpen, Brain, FilePen, FileSearch, FileText, FolderTree, GitBranch, Globe, ListTodo, MessageCircleQuestion,
  Plug, Search, SquareTerminal, Trash2, Wrench,
} from 'lucide-react'

/** 工具名 → 图标 + 一个动词（Codex 那种「Ran / Read / Edited」行首）。 */
export function toolLook(name?: string): { icon: typeof Wrench; verb: string } {
  switch (name) {
    case 'run-command': return { icon: SquareTerminal, verb: '运行' }
    case 'read-file': return { icon: FileText, verb: '读取' }
    case 'list-dir': return { icon: FolderTree, verb: '列目录' }
    case 'search-text': return { icon: Search, verb: '搜索' }
    case 'find-files': return { icon: FileSearch, verb: '找文件' }
    case 'find-symbol': case 'symbol-refs': case 'impact': return { icon: FileSearch, verb: '查符号' }
    case 'write-file': return { icon: FilePen, verb: '写入' }
    case 'edit-file': case 'apply-patch': case 'replace-form': return { icon: FilePen, verb: '修改' }
    case 'list-forms': case 'read-form': return { icon: FileText, verb: '读取' }
    case 'delete-path': return { icon: Trash2, verb: '删除' }
    case 'web-fetch': return { icon: Globe, verb: '抓取' }
    case 'web-search': return { icon: Globe, verb: '搜网页' }
    case 'memory-list': case 'memory-read': case 'memory-write': return { icon: Brain, verb: '笔记' }
    case 'skill': return { icon: BookOpen, verb: '技能' }
    case 'todo-write': return { icon: ListTodo, verb: '更新计划' }
    case 'clarify': return { icon: MessageCircleQuestion, verb: '提问' }
    case 'task': return { icon: Bot, verb: '派给子 agent' }
    case 'execute': return { icon: Plug, verb: '执行脚本' }
    default:
      if (name?.startsWith('worktree')) return { icon: GitBranch, verb: 'worktree' }
      if (name?.includes('__') || name?.includes('.')) return { icon: Plug, verb: 'MCP' }
      return { icon: Wrench, verb: '调用' }
  }
}
