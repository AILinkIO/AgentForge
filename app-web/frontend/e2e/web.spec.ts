import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, type Locator, type Page, test } from '@playwright/test'

// 真模型端到端：一整条用户路径，按顺序跑（后一段用到前一段留下的会话 / 对话）。
// 断言对模型输出放宽：只认关键字 / 结构，不认措辞。

const ROOT = process.env.E2E_ROOT ?? ''
const MODEL = process.env.E2E_MODEL_REF ?? ''
const TURN = 240_000            // 一轮真模型（含工具循环）最多等这么久

test.describe.configure({ mode: 'serial' })

let chatUrl = ''                // 主测会话

// ── 小工具 ─────────────────────────────────────────────────────────────

/** 在「新聊天」页（当前项目、缺省文件夹）发第一句话，落到会话页。 */
async function startChat(page: Page, text: string, project?: string) {
  await page.goto('/new')
  if (project) {
    await page.getByTestId('pick-project').click()
    await page.getByRole('menuitem', { name: project }).click()
  }
  const box = page.getByTestId('new-chat-input')
  await box.fill(text)
  await box.press('Enter')
  await page.waitForURL(/\/chat\//)
  await expect(page.getByTestId('composer-input')).toBeVisible()
  return page.url()
}

/** 在项目对话框里（内嵌的「浏览」）加一个源文件夹。 */
async function addFolder(page: Page, path: string) {
  const dlg = page.getByTestId('project-dialog')
  if (await dlg.getByTestId('add-folder').isVisible()) await dlg.getByTestId('add-folder').click()
  await dlg.getByRole('tab', { name: '浏览' }).click()
  const browser = dlg.getByTestId('folder-browser')
  await browser.getByTestId('folder-path').fill(path)
  await browser.getByRole('button', { name: '前往' }).click()
  await expect(browser.getByTestId('folder-path')).toHaveValue(path)
  await browser.getByTestId('folder-add-current').click()
  await expect(dlg.locator(`[data-testid="project-folder"] [title="${path}"]`)).toBeVisible()
}

const sidebarChat = (page: Page, text: string) => page.getByTestId('sidebar-chat').filter({ hasText: text })

async function send(page: Page, text: string) {
  const box = page.getByTestId('composer-input')
  await box.fill(text)
  await box.press('Enter')
  await expect(page.getByTestId('user-msg').filter({ hasText: text.slice(0, 20) }).last()).toBeVisible()
}

/** 等这一轮落地（不忙、也没在等审批 / 提问）。 */
async function waitIdle(page: Page, timeout = TURN) {
  await expect(page.getByTestId('session-status')).toHaveAttribute('data-busy', 'false', { timeout })
}

/** 等这一轮落地；途中冒出来的审批（比如子 agent 要跑命令）一律批准，并记下来源。 */
async function waitIdleApproving(page: Page, timeout = TURN): Promise<string[]> {
  const from: string[] = []
  const deadline = Date.now() + timeout
  const status = page.getByTestId('session-status')
  while (Date.now() < deadline) {
    if ((await status.getAttribute('data-busy')) === 'false') return from
    const card = page.getByTestId('approval-card')
    if (await card.isVisible()) {
      from.push((await card.textContent()) ?? '')
      await card.getByRole('button', { name: /^是，/ }).first().click()
      await expect(card).toBeHidden({ timeout: 30_000 }).catch(() => {})
    }
    await page.waitForTimeout(500)
  }
  throw new Error('这一轮在时限内没有落地')
}

async function snapshotOf(page: Page, url: string) {
  const id = url.split('/chat/')[1]
  const r = await page.request.get(`/api/sessions/${id}`)
  expect(r.ok()).toBe(true)
  return r.json()
}

function lastAssistant(page: Page): Locator {
  return page.getByTestId('assistant-msg').last()
}

// ── 用例 ───────────────────────────────────────────────────────────────

test('概览页：统计卡、运行环境、实时连接', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '概览' })).toBeVisible()
  for (const t of ['开着的会话', '在跑的子 agent', '本次用量（token）', '本次花费']) await expect(page.getByText(t).first()).toBeVisible()
  await expect(page.getByText('运行环境')).toBeVisible()
  await expect(page.getByText(MODEL).first()).toBeVisible()
  await expect(page.getByText('untrusted').first()).toBeVisible()
  await expect(page.getByText('已连接')).toBeVisible()
})

test('项目：没有项目时引导添加；走文件夹选择器加进来', async ({ page }) => {
  await page.goto('/new')
  await expect(page.getByText('先添加一个项目')).toBeVisible()
  await page.getByTestId('empty-add-project').click()
  const dlg = page.getByTestId('project-dialog')
  await expect(dlg.getByRole('heading', { name: '添加项目' })).toBeVisible()
  // 选择器：从家目录起，逐级 / 直接敲路径都行；这里敲路径
  await addFolder(page, ROOT)
  await expect(dlg.getByTestId('project-name')).toHaveValue('root')        // 名字取文件夹名
  await dlg.getByTestId('project-name').fill('E2E 项目')
  await dlg.getByTestId('project-save').click()
  await expect(dlg).toBeHidden()
  await page.waitForURL(/\/new\?project=/)
  await expect(page.getByTestId('sidebar-project').filter({ hasText: 'E2E 项目' })).toBeVisible()
  await expect(page.getByText('要在「E2E 项目」里做些什么？')).toBeVisible()
  await expect(page.getByTestId('pick-project')).toContainText('E2E 项目')
  await expect(page.getByRole('button', { name: '看看这个项目是做什么的' })).toBeVisible()
})

test('项目：建议列出 git 仓库；浏览能逐级点进去、走面包屑、新建文件夹', async ({ page }) => {
  await page.goto('/new')
  await page.getByTestId('add-project').click()
  const dlg = page.getByTestId('project-dialog')
  await expect(dlg.getByRole('heading', { name: '添加项目' })).toBeVisible()
  await expect(dlg.getByTestId('folder-suggest')).toBeVisible()             // 新建时默认打开「建议」
  await dlg.getByRole('tab', { name: '浏览' }).click()
  const browser = dlg.getByTestId('folder-browser')
  await browser.getByTestId('folder-path').fill(ROOT)
  await browser.getByRole('button', { name: '前往' }).click()
  await browser.getByTestId('folder-entry').filter({ hasText: 'notes' }).click()
  await expect(browser.getByTestId('folder-path')).toHaveValue(join(ROOT, 'notes'))
  await browser.getByTestId('folder-crumbs').getByRole('button', { name: 'root', exact: true }).click()   // 面包屑回上一级
  await expect(browser.getByTestId('folder-path')).toHaveValue(ROOT)
  await browser.getByRole('button', { name: '新建' }).click()
  await browser.getByPlaceholder('新文件夹的名字').fill('made-by-picker')
  await browser.getByRole('button', { name: '建立' }).click()
  await expect(browser.getByTestId('folder-path')).toHaveValue(join(ROOT, 'made-by-picker'))
  expect(existsSync(join(ROOT, 'made-by-picker'))).toBe(true)
  // 加进来：名字自动取文件夹名；移出后名字跟着清空
  await browser.getByTestId('folder-add-current').click()
  await expect(dlg.getByTestId('project-name')).toHaveValue('made-by-picker')
  await dlg.getByTestId('project-folder').getByTitle('移出').click()
  await expect(dlg.getByTestId('project-name')).toHaveValue('')
  await expect(dlg.getByTestId('project-save')).toBeDisabled()
  await dlg.getByRole('button', { name: '取消' }).click()
  await expect(dlg).toBeHidden()
})

test('纯对话：从新聊天页开在项目里、流式回答、标题、用量', async ({ page }) => {
  chatUrl = await startChat(page, '请只回复 OK-AGENTFORGE 这几个字符，不要调用任何工具。')
  await waitIdle(page)
  await expect(lastAssistant(page)).toContainText('OK-AGENTFORGE')
  await expect(page.getByTestId('chat-project')).toContainText('E2E 项目')
  // 侧栏：对话挂在项目底下，标题取第一句话
  await expect(page.getByTestId('sidebar-project').filter({ hasText: 'E2E 项目' }).getByTestId('sidebar-chat').filter({ hasText: '请只回复' })).toBeVisible()
  // 用量读数有了
  const ctx = page.getByTestId('side-context')
  await expect(ctx.getByText('输入')).toBeVisible()
  await expect(ctx.locator('dd').first()).not.toHaveText('0')
})

test('项目：多个源文件夹、项目指令与预设、编辑与移除', async ({ page }) => {
  await page.goto('/new')
  await page.getByTestId('add-project').click()
  const dlg = page.getByTestId('project-dialog')
  await addFolder(page, join(ROOT, 'src'))
  await addFolder(page, join(ROOT, 'notes'))
  await dlg.getByTestId('project-name').fill('双文件夹')
  await dlg.getByTestId('project-settings-toggle').click()
  await dlg.getByTestId('project-instructions').fill('你的每一个回答都必须以 PROJ-OK 这几个字符开头。')
  await dlg.getByTestId('project-preset').filter({ hasText: '只读' }).click()
  await expect(dlg.getByTestId('project-preset').filter({ hasText: '只读' })).toHaveAttribute('aria-checked', 'true')
  await dlg.getByTestId('project-save').click()
  await page.waitForURL(/\/new\?project=/)
  // 选第二个源文件夹开会话
  await expect(page.getByTestId('pick-folder')).toContainText('src')
  await page.getByTestId('pick-folder').click()
  await page.getByRole('menuitem', { name: join(ROOT, 'notes') }).click()
  await expect(page.getByTestId('pick-folder')).toContainText('notes')
  await expect(page.getByText('项目权限：read-only')).toBeVisible()
  await page.getByTestId('new-chat-input').fill('用 list-dir 列出当前工作根下的条目，然后按项目指令回答，告诉我有哪些文件。')
  await page.getByTestId('new-chat-input').press('Enter')
  await page.waitForURL(/\/chat\//)
  const url = page.url()
  await waitIdle(page)
  await expect(page.getByTestId('chat-project')).toContainText('双文件夹')
  await expect(page.getByTestId('chat-project')).toContainText('notes')
  const snap = await snapshotOf(page, url)
  expect(snap.root).toBe(join(ROOT, 'notes'))
  await expect(page.locator('[data-testid="tool-row"][data-tool="list-dir"]').last()).toBeVisible()
  await expect(lastAssistant(page)).toContainText('PROJ-OK')               // 项目指令进了系统提示
  await expect(lastAssistant(page)).toContainText('secret-word')           // 真在 notes 里

  // 编辑：改名，侧栏跟着变
  const item = page.getByTestId('sidebar-project').filter({ hasText: '双文件夹' })
  await item.hover()
  await item.getByTestId('project-edit').click()
  await expect(dlg.getByRole('heading', { name: '编辑项目' })).toBeVisible()
  await expect(dlg.getByTestId('project-folder')).toHaveCount(2)
  await dlg.getByTestId('project-name').fill('双文件夹（改）')
  await dlg.getByTestId('project-save').click()
  await expect(page.getByTestId('sidebar-project').filter({ hasText: '双文件夹（改）' })).toBeVisible()

  // 有开着的会话时移除会被拒；关掉后可以，对话归到「未归类」
  const chat = page.getByTestId('sidebar-project').filter({ hasText: '双文件夹（改）' }).getByTestId('sidebar-chat').first()
  await chat.hover()
  await chat.getByTestId('close-chat').click()
  const item2 = page.getByTestId('sidebar-project').filter({ hasText: '双文件夹（改）' })
  await item2.hover()
  await item2.getByTestId('project-edit').click()
  await dlg.getByRole('button', { name: '移除项目' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '移除' }).click()
  await expect(page.getByTestId('sidebar-project').filter({ hasText: '双文件夹' })).toHaveCount(0)
  expect(existsSync(join(ROOT, 'notes', 'secret-word.txt'))).toBe(true)     // 文件没动
  await page.getByRole('link', { name: '历史对话' }).click()
  await page.getByTestId('history-project').click()
  await page.getByRole('option', { name: '未归类' }).click()
  await expect(page.getByRole('row').filter({ hasText: '用 list-dir' })).toBeVisible()
})

test('只读工具：read-file 行、展开看完整结果', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '用 read-file 工具读 notes/secret-word.txt，然后告诉我文件里的口令是什么。')
  await waitIdle(page)
  const row = page.locator('[data-testid="tool-row"][data-tool="read-file"]').last()
  await expect(row).toBeVisible()
  await expect(row).toHaveAttribute('data-state', 'done')
  await expect(lastAssistant(page)).toContainText('PINEAPPLE-42')
  await row.getByRole('button').first().click()
  await expect(row.locator('pre').filter({ hasText: 'PINEAPPLE-42' })).toBeVisible()
})

test('审批：批准后命令执行、文件落地', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '用 run-command 工具执行这条命令：echo hello-from-shell > out.txt && cat out.txt ，执行完告诉我输出。')
  const card = page.getByTestId('approval-card')
  await expect(card).toBeVisible({ timeout: TURN })
  await expect(card).toContainText('hello-from-shell')
  await expect(page.getByTestId('session-status')).toHaveAttribute('data-status', 'AWAITING_APPROVAL')
  await card.getByRole('button', { name: /是，执行/ }).click()
  await expect(card).toBeHidden({ timeout: 30_000 })
  await waitIdle(page)
  expect(readFileSync(join(ROOT, 'out.txt'), 'utf8')).toContain('hello-from-shell')
  await expect(page.locator('[data-testid="tool-row"][data-tool="run-command"]').last()).toHaveAttribute('data-state', 'done')
  await expect(lastAssistant(page)).toContainText('hello-from-shell')
})

test('审批：拒绝并附话，文件不会出现', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '用 run-command 工具执行：touch should-not-exist.txt 。如果被拒绝，就回复“已放弃”，不要再尝试别的办法。')
  const card = page.getByTestId('approval-card')
  await expect(card).toBeVisible({ timeout: TURN })
  await card.getByRole('button', { name: /否，不做/ }).click()
  const fb = card.getByPlaceholder(/告诉它该怎么做/)
  await expect(fb).toBeVisible()
  await fb.fill('不要建这个文件')
  await fb.press('Enter')
  await waitIdle(page)
  expect(existsSync(join(ROOT, 'should-not-exist.txt'))).toBe(false)
  await expect(page.locator('[data-testid="tool-row"][data-tool="run-command"]').last()).toHaveAttribute('data-state', 'error')
})

test('审批：快捷键 n = 停下这一轮', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '用 run-command 工具执行：mkdir -p tmp-dir-e2e')
  const card = page.getByTestId('approval-card')
  await expect(card).toBeVisible({ timeout: TURN })
  await page.locator('body').click({ position: { x: 5, y: 5 } })   // 焦点离开输入框，字母键才归审批卡
  await page.keyboard.press('n')
  await expect(card).toBeHidden({ timeout: 30_000 })
  await waitIdle(page)
  expect(existsSync(join(ROOT, 'tmp-dir-e2e'))).toBe(false)
})

test('提问：clarify 面板选项并提交', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '在做任何别的事之前，先调用 clarify 工具问我一个问题：“喜欢哪种颜色？”，选项只有“红色”和“蓝色”两个。拿到答案后用一句话复述我的选择。')
  const card = page.getByTestId('question-card')
  await expect(card).toBeVisible({ timeout: TURN })
  await expect(page.getByTestId('session-status')).toHaveAttribute('data-status', 'AWAITING_QUESTION')
  await card.getByRole('button', { name: /蓝色/ }).click()
  await card.getByRole('button', { name: '提交回答' }).click()
  await expect(card).toBeHidden({ timeout: 30_000 })
  await waitIdle(page)
  await expect(lastAssistant(page)).toContainText('蓝')
})

test('计划：todo-write 进右栏', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '调用 todo-write 工具写一个三步计划：第一步“读代码”标为已完成，第二步“写测试”、第三步“提交”未完成。写完直接结束，不用做别的。')
  await waitIdle(page)
  const plan = page.getByTestId('side-plan')
  await expect(plan.getByTestId('todo-item')).toHaveCount(3)
  await expect(plan.locator('[data-testid="todo-item"][data-status="DONE"]')).toHaveCount(1)
  await expect(plan).toContainText('1/3')
})

test('Markdown：表格与代码高亮、复制按钮', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '不要用工具。用 markdown 输出：一个两列三行的表格（列名“名字”“值”），再给一段 python 代码块，内容是 def hello(): return 1')
  await waitIdle(page)
  const msg = lastAssistant(page)
  await expect(msg.locator('table')).toBeVisible()
  await expect(msg.locator('th').first()).toBeVisible()
  await expect(msg.locator('.code-block pre code')).toContainText('def hello')
  await expect(msg.locator('.code-block .hljs-keyword').first()).toBeVisible()
  await msg.locator('.code-block').hover()
  await expect(msg.getByRole('button', { name: '复制' })).toBeVisible()
})

test('按停：流式中途停下', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '不要用工具，写一篇至少 3000 字的长文，主题是“软件测试的历史”，分很多小节慢慢写。')
  await expect(page.locator('[data-testid="assistant-msg"][data-streaming="true"], [data-testid="thinking"]').last()).toBeVisible({ timeout: 90_000 })
  await page.getByTestId('stop').click()
  await waitIdle(page, 30_000)
  await expect(page.getByTestId('stop')).toBeHidden()
})

test('子 agent：task 派活、卡片与 Agents 页', async ({ page }) => {
  await page.goto(chatUrl)
  await send(page, '必须使用 task 工具，把“列出工作根目录下的所有文件并统计个数”这件事交给一个子 agent 去做，等它做完后把结果告诉我。')
  const card = page.getByTestId('subagent-card').last()
  await expect(card).toBeVisible({ timeout: TURN })
  const approvals = await waitIdleApproving(page)
  // 子 agent 的审批摆在主会话上，并标明来自谁
  for (const a of approvals) expect(a).toContain('来自')
  await expect(card).toHaveAttribute('data-running', 'false')
  await card.getByRole('button').first().click()          // 展开步骤
  await expect(page.getByTestId('side-subagents')).not.toContainText('没有派过子 agent')
  await page.getByRole('link', { name: 'Agents' }).click()
  await expect(page).toHaveURL(/\/agents$/)
  await expect(page.getByRole('heading', { name: 'Agents' })).toBeVisible()
  await expect(page.locator('li').filter({ hasText: '步' }).first()).toBeVisible()
})

test('附件：上传图片出现在输入区，可以清掉', async ({ page }) => {
  await page.goto(chatUrl)
  // 1×1 PNG
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==', 'base64')
  await page.locator('input[type="file"]').setInputFiles({ name: 'dot.png', mimeType: 'image/png', buffer: png })
  await expect(page.getByTestId('attachments')).toContainText('dot.png')
  await page.getByTestId('attachments').getByRole('button', { name: /清掉/ }).click()
  await expect(page.getByTestId('attachments')).toBeHidden()
})

test('改标题、刷新后仍在（SPA 路由）', async ({ page }) => {
  await page.goto(chatUrl)
  await page.getByTitle('改标题').click()
  const input = page.locator('header input[name="t"]')
  await input.fill('E2E 主会话')
  await input.press('Enter')
  await expect(sidebarChat(page, 'E2E 主会话')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('banner').getByText('E2E 主会话')).toBeVisible()
  await expect(page.getByTestId('user-msg').first()).toBeVisible()
})

async function compactViaMenu(page: Page) {
  await page.getByRole('banner').getByRole('button').filter({ has: page.locator('svg.lucide-ellipsis') }).click()
  await page.getByRole('menuitem', { name: '压缩上下文' }).click()
}


test('压缩上下文：真压了、前情提要可展开、之后照常对话', async ({ page }) => {
  await page.goto(chatUrl)
  // 压缩原样留下最近两轮：先垫两轮短的，让前面的长文 / 子 agent 那几轮落进"可压的头"
  for (const w of ['PAD-ONE', 'PAD-TWO']) {
    await send(page, `请只回复 ${w} 这几个字符，不要调用任何工具。`)
    await waitIdle(page)
  }
  const before = (await snapshotOf(page, chatUrl)).compactions as number
  const msgsBefore = await page.getByTestId('user-msg').count()
  await compactViaMenu(page)
  await expect(page.getByText('开始压缩上下文')).toBeVisible()
  // 成了才会有这条（压掉几条 + 摘要多长）；没成会是"压缩没成：…"
  await expect(page.getByText(/上下文已压缩：\d+ 条折成一份 \d+ 字的前情提要/)).toBeVisible({ timeout: TURN })
  await waitIdle(page)
  // 后端记了一条压缩指针
  expect((await snapshotOf(page, chatUrl)).compactions).toBe(before + 1)
  await expect(page.getByTestId('compaction-count')).toHaveText(`已压缩 ${before + 1} 次`)
  // 记录区：分隔线 + 前情提要（默认收起，展开有内容）；原来的对话还在（只是不再发给模型）
  await expect(page.getByTestId('compaction-divider').last()).toBeVisible()
  const summary = page.getByTestId('compaction-summary').last()
  await expect(summary).toBeVisible()
  await summary.getByRole('button', { name: /前情提要/ }).click()
  const body = summary.locator('.chat-md')
  await expect(body).toBeVisible()
  expect(((await body.textContent()) ?? '').trim().length).toBeGreaterThan(50)
  expect(await page.getByTestId('user-msg').count()).toBe(msgsBefore)
  // 压完以后照常对话（请求走压缩后的有效历史）
  await send(page, '请只回复 AFTER-COMPACT 这几个字符，不要调用任何工具。')
  await waitIdle(page)
  await expect(lastAssistant(page)).toContainText('AFTER-COMPACT')
})

test('压缩上下文：对话太短时当场说明，不假装开始', async ({ page }) => {
  const url = await startChat(page, '请只回复 SHORT 这几个字符，不要调用任何工具。')
  await waitIdle(page)
  await compactViaMenu(page)
  await expect(page.getByText('对话还短，没有值得压的')).toBeVisible()
  await expect(page.getByText('开始压缩上下文')).toHaveCount(0)
  await expect(page.getByTestId('compaction-count')).toHaveCount(0)
  expect((await snapshotOf(page, url)).compactions).toBe(0)
  // 关掉这段短会话，免得后面的用例看到多余的会话
  const item = sidebarChat(page, '请只回复 SHORT')
  await item.hover()
  await item.getByTestId('close-chat').click()
})

test('历史：关掉会话后在历史里搜到、重新打开、删除', async ({ page }) => {
  await page.goto(chatUrl)
  const item = sidebarChat(page, 'E2E 主会话')
  await item.hover()
  await item.getByTestId('close-chat').click()
  await expect(page).toHaveURL(/\/new$/)
  // 关掉后仍挂在项目底下（存着的对话）
  await expect(page.getByTestId('sidebar-project').filter({ hasText: 'E2E 项目' }).getByTestId('sidebar-conv').filter({ hasText: 'E2E 主会话' })).toBeVisible()
  await page.getByRole('link', { name: '历史对话' }).click()
  const row = page.getByRole('row').filter({ hasText: 'E2E 主会话' })
  await expect(row).toBeVisible()
  // 全文搜索
  await page.getByPlaceholder('全文搜索（至少 3 个字）').fill('PINEAPPLE')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByText(/的搜索结果/)).toBeVisible()
  await expect(page.locator('li').filter({ hasText: 'PINEAPPLE' }).first()).toBeVisible()
  // 重新打开
  await row.getByRole('button', { name: '打开' }).click()
  await page.waitForURL(/\/chat\//)
  await expect(page.getByText('PINEAPPLE-42').first()).toBeVisible()
  chatUrl = page.url()
  // 再关掉，删除
  await expect(page.getByTestId('chat-project')).toContainText('E2E 项目')     // 重开回到原项目
  const again = sidebarChat(page, 'E2E 主会话')
  await again.hover()
  await again.getByTestId('close-chat').click()
  await page.getByRole('link', { name: '历史对话' }).click()
  const r2 = page.getByRole('row').filter({ hasText: 'E2E 主会话' })
  await r2.getByRole('button').last().click()
  await page.getByRole('alertdialog').getByRole('button', { name: '删除' }).click()
  await expect(page.getByRole('row').filter({ hasText: 'E2E 主会话' })).toHaveCount(0)
})

const HOME = process.env.E2E_HOME ?? ''
const MODEL_ID = process.env.E2E_MODEL_ID ?? ''
const PROVIDER_BASE = process.env.E2E_PROVIDER_BASE ?? ''

async function setDefaultModel(page: Page, ref: string) {
  await page.goto('/models')
  const row = page.locator(`[data-testid="model-row"][data-ref="${ref}"]`)
  await expect(row).toBeVisible()
  if (await row.getByText('默认', { exact: true }).count() === 0) await row.getByRole('button', { name: '设为默认' }).click()
  await expect(row.getByText('默认', { exact: true })).toBeVisible()
  expect((await (await page.request.get('/api/info')).json()).model).toBe(ref)
}

test('凭证：列出来路、手动添加（写进 providers.json）、用它的模型真聊一轮、移除', async ({ page }) => {
  await page.goto('/credentials')
  const mm = page.locator('[data-testid="credential-row"][data-id="minimax"]')
  await expect(mm).toContainText('providers.json')
  await expect(mm).toContainText('环境变量')
  await expect(mm).toContainText('MINIMAX_API_KEY')
  // 手动填写：同一个端点换个 id，key 指向同一个环境变量（不在页面上敲真 key）
  await page.getByTestId('add-credential').click()
  const dlg = page.getByTestId('credential-dialog')
  await dlg.getByRole('tab', { name: '手动填写' }).click()
  await dlg.getByTestId('credential-id').fill('e2e-gw')
  await dlg.getByTestId('credential-label').fill('E2E 网关')
  await dlg.getByTestId('credential-base').fill(PROVIDER_BASE)
  await dlg.getByText('Key 的环境变量（可选）').locator('..').getByRole('textbox').fill('MINIMAX_API_KEY')
  await dlg.getByTestId('credential-model').fill(MODEL_ID)
  await dlg.getByTestId('credential-save').click()
  await expect(dlg).toBeHidden()
  const gw = page.locator('[data-testid="credential-row"][data-id="e2e-gw"]')
  await expect(gw).toContainText('E2E 网关')
  await expect(gw).toContainText('MINIMAX_API_KEY')
  expect(readFileSync(join(HOME, 'providers.json'), 'utf8')).toContain('"e2e-gw"')

  // 设成默认模型，开一段新聊天真跑一轮
  const ref = `e2e-gw/${MODEL_ID}`
  await setDefaultModel(page, ref)
  const url = await startChat(page, '请只回复 VIA-GW 这几个字符，不要调用任何工具。')
  await waitIdle(page)
  await expect(lastAssistant(page)).toContainText('VIA-GW')
  expect((await snapshotOf(page, url)).session.model).toBe(ref)
  const chat = sidebarChat(page, '请只回复 VIA-GW')
  await chat.hover()
  await chat.getByTestId('close-chat').click()

  // 还原默认模型，再移除
  await setDefaultModel(page, MODEL)
  await page.goto('/credentials')
  await gw.getByTestId('credential-remove').click()
  await page.getByRole('alertdialog').getByRole('button', { name: '移除' }).click()
  await expect(gw).toHaveCount(0)
  expect(readFileSync(join(HOME, 'providers.json'), 'utf8')).not.toContain('"e2e-gw"')
  await page.goto('/models')
  await expect(page.locator(`[data-testid="model-row"][data-ref="${ref}"]`)).toHaveCount(0)
})

test('模型目录：同步 models.dev、导入 api.json、从目录添加凭证', async ({ page }) => {
  await page.goto('/models')
  await page.getByTestId('catalog-sync').click()
  await expect(page.getByText(/同步完成：\d+ 家厂商、\d+ 个模型/)).toBeVisible({ timeout: 120_000 })
  await expect(page.getByTestId('catalog-status')).toContainText('models.dev 同步于')

  // 导入一份只有一家的 api.json（端点、key 环境变量都指向测试用的那家）
  const apiJson = { e2ecat: {
    id: 'e2ecat', name: 'E2E Cat', npm: '@ai-sdk/anthropic', api: PROVIDER_BASE, env: ['MINIMAX_API_KEY'],
    models: { [MODEL_ID]: { id: MODEL_ID, name: 'E2E Flash', tool_call: true, limit: { context: 200000, output: 16000 } } },
  } }
  await page.locator('input[type="file"][accept*="json"]').setInputFiles({
    name: 'api.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(apiJson)),
  })
  await expect(page.getByText('导入完成：1 家厂商、1 个模型')).toBeVisible()
  await expect(page.getByTestId('catalog-status')).toContainText('1 家厂商')

  await page.goto('/credentials')
  await page.getByTestId('add-credential').click()
  const dlg = page.getByTestId('credential-dialog')
  await expect(dlg.getByRole('tab', { name: '从 models.dev 选择' })).toHaveAttribute('data-state', 'active')
  await dlg.getByTestId('catalog-search').fill('e2e')
  await dlg.getByTestId('catalog-provider').filter({ hasText: 'E2E Cat' }).click()
  await dlg.getByTestId('credential-save').click()
  await expect(dlg).toBeHidden()
  const row = page.locator('[data-testid="credential-row"][data-id="e2ecat"]')
  await expect(row).toContainText('models.dev')
  await expect(row).toContainText('MINIMAX_API_KEY')            // key 来自目录列的环境变量

  await page.goto('/models')
  await page.getByTestId('model-source').click()
  await page.getByRole('option', { name: 'models.dev' }).click()
  await expect(page.locator(`[data-testid="model-row"][data-ref="e2ecat/${MODEL_ID}"]`)).toBeVisible()
  await expect(page.locator(`[data-testid="model-row"][data-ref="${MODEL}"]`)).toHaveCount(0)   // 那条来自 providers.json，被筛掉

  await page.goto('/credentials')
  await row.getByTestId('credential-remove').click()
  await page.getByRole('alertdialog').getByRole('button', { name: '移除' }).click()
  await expect(row).toHaveCount(0)
})

test('模型页：默认模型、搜索、来源筛选', async ({ page }) => {
  await page.goto('/models')
  await expect(page.getByRole('heading', { name: '模型' })).toBeVisible()
  const row = page.locator(`[data-testid="model-row"][data-ref="${MODEL}"]`)
  await expect(row.getByText('默认', { exact: true })).toBeVisible()
  await expect(row).toContainText('providers.json')
  await page.getByPlaceholder('搜索模型').fill('no-such-model')
  await expect(page.getByTestId('model-row')).toHaveCount(0)
  await page.getByPlaceholder('搜索模型').fill('')
  await page.getByTestId('model-source').click()
  await page.getByRole('option', { name: 'providers.json' }).click()
  await expect(row).toBeVisible()
})

test('工具页：过滤与 MCP 空状态', async ({ page }) => {
  await page.goto('/tools')
  await expect(page.getByRole('heading', { name: '工具与 MCP' })).toBeVisible()
  await page.getByPlaceholder('过滤').fill('run-command')
  const titles = page.locator('[data-slot="card-title"]')
  await expect(titles.filter({ hasText: 'run-command' })).toBeVisible()
  await expect(titles.filter({ hasText: 'read-file' })).toHaveCount(0)      // 名字和说明里都没提 run-command 的不出现
  expect(await titles.count()).toBeLessThan(10)
  await page.getByRole('tab', { name: /MCP 服务器/ }).click()
  await expect(page.getByText('没有 MCP 服务器')).toBeVisible()
})

test('权限页：切预设、侧栏跟着变', async ({ page }) => {
  await page.goto('/permissions')
  await page.getByRole('button', { name: /只读/ }).click()
  await expect(page.getByRole('button', { name: /只读/ }).getByText('当前')).toBeVisible()
  await expect(page.getByText('approval=on-request').first()).toBeVisible()
  await expect(page.locator('[data-sidebar="footer"]')).toContainText('只读')
  await page.getByRole('button', { name: /默认/ }).click()
  await expect(page.getByRole('button', { name: /默认/ }).getByText('当前')).toBeVisible()
})

test('命令面板：⌘K 跳页面', async ({ page }) => {
  await page.goto('/')
  await page.keyboard.press('Control+k')
  const dlg = page.getByRole('dialog')
  await expect(dlg).toBeVisible()
  await dlg.getByPlaceholder('输入命令或搜索…').fill('权限')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/permissions$/)
})

test('主题：明暗切换、配色预设、刷新后保留', async ({ page }) => {
  await page.goto('/')
  const html = page.locator('html')
  const wasDark = (await html.getAttribute('class'))?.includes('dark') ?? false
  await page.getByTitle('切换明暗').click()
  if (wasDark) await expect(html).not.toHaveClass(/dark/)
  else await expect(html).toHaveClass(/dark/)
  await page.getByTitle('界面设置').click()
  await page.getByRole('button', { name: '森绿' }).click()
  await expect(html).toHaveAttribute('data-preset', 'green')
  await page.keyboard.press('Escape')
  await page.reload()
  await expect(html).toHaveAttribute('data-preset', 'green')
  await page.getByTitle('界面设置').click()
  await page.getByRole('button', { name: '恢复默认' }).click()
  await expect(html).toHaveAttribute('data-preset', 'neutral')
})

test('窄屏：侧栏收起、右栏用抽屉', async ({ page }) => {
  await startChat(page, '请只回复 NARROW 这几个字符，不要调用任何工具。')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('composer-input')).toBeVisible()
  await expect(page.locator('[data-sidebar="sidebar"]').first()).toBeHidden()
  await page.getByTitle('上下文与计划').click()
  await expect(page.getByRole('dialog').getByText('上下文与计划')).toBeVisible()
})
