import { test, expect, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const output = resolve('../.evidence/materials-ui-20261022/screens')
const timestamp = '2026-10-22T08:00:00Z'
// 仅用于明确隔离的视觉验收，不会写入日常资料。
const samples = [
  { id: 'resume', kind: 'RESUME', name: '后端开发简历', content: '', documents: [{ id: 'resume-md', name: '后端开发简历.md', text: '合成验收资料：负责知识库服务的接口设计、检索链路和异常处理。使用 Java、Spring Boot 与 SQLite，补充设计取舍与个人职责。', truncated: true, createdAt: timestamp }] },
  { id: 'project', kind: 'PROJECT', name: '企业知识库问答系统', content: '项目简介：面向团队的知识库问答服务。\n技术栈：Java、Spring Boot、SQLite。\n个人职责：检索服务与接口设计，处理资料导入、错误恢复和结果验证。', documents: [], repository: { repository: 'acme/knowledge-base', status: 'PARTIAL', url: 'https://github.com/acme/knowledge-base', warnings: ['合成提示：部分目录未读取。'] } },
  { id: 'jd', kind: 'JD', name: 'Java 后端开发岗位', content: '负责业务服务开发，熟悉数据库、缓存和分布式基础。能够清晰说明项目中的职责与技术选择。', documents: [] },
  { id: 'project-2', kind: 'PROJECT', name: '资料处理与检索服务', content: '处理多种文档格式，构建可恢复的资料导入流程。通过失败用例核对重复上传、文本截断和事务边界。', documents: [] },
  { id: 'project-long', kind: 'PROJECT', name: '长名称边界验证'.repeat(12), content: '合成完整正文\n'.repeat(100), documents: Array.from({ length: 20 }, (_, i) => ({ id: `doc-${i}`, name: `附件${i + 1}-${'长文件名'.repeat(12)}.md`, text: `合成附件${i + 1}：${'可读正文'.repeat(100)}`, truncated: i === 0, createdAt: timestamp })), repository: { repository: `acme/${'long-repository-'.repeat(6)}`, url: 'https://github.com/acme/example', status: 'FAILED', error: '合成错误：GitHub 限流，稍后重新关联。' } },
].map((material, i) => ({ ...material, createdAt: timestamp, updatedAt: `2026-10-22T0${8 - i}:00:00Z` }))

async function syntheticList(page: Page, items = samples) {
  const writes: string[] = []
  await page.route(/\/api\/materials(?:\/.*)?(?:\?.*)?$/, async route => {
    const request = route.request()
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/materials') return route.fulfill({ json: items })
    writes.push(`${request.method()} ${new URL(request.url()).pathname}`)
    return route.fulfill({ status: 405, json: { message: '本视觉样本禁止写入' } })
  })
  return writes
}
async function noOverflow(page: Page, width: number) {
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(width)
}
async function capture(page: Page, name: string) {
  await mkdir(output, { recursive: true })
  await page.screenshot({ path: resolve(output, `${name}.png`), fullPage: await page.getByRole('dialog').count() === 0, animations: 'disabled' })
}

for (const [width, columns] of [[375, 1], [768, 2], [819, 2], [821, 1], [1024, 2], [1440, 3], [1920, 3]]) {
  test(`资料概览与详情 ${width}px`, async ({ page }) => {
    const writes = await syntheticList(page)
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
    await page.setViewportSize({ width: width!, height: 1000 })
    await page.goto('/materials')
    await expect(page.locator('.material-card')).toHaveCount(5)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(await page.locator('.material-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(columns)
    await noOverflow(page, width!)
    await capture(page, `overview-${width}`)
    const opener = page.locator('[data-material-preview="project-long"]')
    await opener.focus()
    await page.keyboard.press('Enter')
    const dialog = page.getByRole('dialog')
    await expect(dialog).toContainText('合成完整正文')
    await expect(dialog).toContainText('GitHub 限流')
    await expect(dialog.locator('.document-item')).toHaveCount(20)
    await expect(dialog).toContainText('截断')
    const bounds = await dialog.boundingBox()
    expect(Math.round(bounds!.width)).toBe(width! <= 768 ? width : 760)
    await expect(dialog.getByRole('button', { name: '关闭', exact: true })).toBeInViewport()
    await dialog.locator('.dialog-drawer-body').evaluate(element => { element.scrollTop = element.scrollHeight })
    await noOverflow(page, width!)
    await capture(page, `detail-${width}`)
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(opener).toBeFocused()
    await expect(opener).toBeInViewport()
    await page.locator('[data-kind="PROJECT"]').click()
    await page.getByLabel('搜索资料名称或手工正文').fill('  JAVA  ')
    await expect(page.locator('.material-card')).toHaveCount(1)
    await page.locator('[data-material-preview="project"]').click()
    await page.getByRole('button', { name: '编辑正文' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(1)
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(1)
    await page.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByLabel('搜索资料名称或手工正文')).toHaveValue('  JAVA  ')
    await expect(page.locator('[data-kind="PROJECT"]')).toHaveAttribute('aria-pressed', 'true')
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}

test('空库、加载与错误重试，JD详情没有练习按钮', async ({ page }) => {
  let resolveLoad!: () => void
  const gate = new Promise<void>(resolve => { resolveLoad = resolve })
  let count = 0
  await page.route('**/api/materials', async route => {
    count++
    if (count === 1) { await gate; return route.fulfill({ status: 500, json: { message: '合成读取失败' } }) }
    return route.fulfill({ json: count === 2 ? [] : [samples[2]] })
  })
  await page.goto('/materials')
  await expect(page.getByText('正在读取本机资料…')).toBeVisible()
  await expect(page.getByRole('button', { name: '新增资料', exact: true })).toBeDisabled()
  await expect(page.locator('.material-count')).toHaveCount(0)
  resolveLoad()
  await expect(page.getByRole('alert')).toContainText('合成读取失败')
  await page.getByRole('button', { name: '重新加载' }).click()
  await expect(page.getByRole('heading', { name: '还没有资料', exact: true })).toBeVisible()
  await capture(page, 'empty')
  await page.reload()
  await page.locator('[data-material-preview="jd"]').click()
  await expect(page.getByRole('dialog').getByRole('link')).toHaveCount(0)
})

test('拖入只待确认，键盘选择、无效多文件与搜索空结果', async ({ page }) => {
  const writes = await syntheticList(page)
  await page.goto('/materials')
  await page.getByRole('button', { name: '新增资料', exact: true }).focus()
  await page.keyboard.press('Enter')
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '选择简历文件' }).focus()
  await expect(dialog.getByRole('button', { name: '选择简历文件' })).toBeFocused()
  const transfer = await page.evaluateHandle(() => {
    const data = new DataTransfer()
    data.items.add(new File(['合成正文'], '拖入简历.md', { type: 'text/markdown' }))
    return data
  })
  await dialog.locator('[data-dropzone]').dispatchEvent('drop', { dataTransfer: transfer })
  await expect(dialog).toContainText('待上传：拖入简历.md')
  expect(writes).toEqual([])
  await capture(page, 'upload-pending')
  await dialog.getByRole('button', { name: '取消选择' }).click()
  await expect(dialog).not.toContainText('待上传：')
  const multiple = await page.evaluateHandle(() => {
    const data = new DataTransfer()
    for (const name of ['一.md', '二.md']) data.items.add(new File(['合成正文'], name))
    return data
  })
  await dialog.locator('[data-dropzone]').dispatchEvent('drop', { dataTransfer: multiple })
  await expect(dialog.getByRole('alert')).toContainText('一份')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '新增资料', exact: true })).toBeFocused()
  await page.getByLabel('搜索资料名称或手工正文').fill('附件1')
  await expect(page.getByText('没有匹配的资料')).toBeVisible()
  await page.getByRole('button', { name: '清空搜索' }).click()
  await expect(page.locator('.material-card')).toHaveCount(5)
  expect(writes).toEqual([])
})

test('768px录入字号、触控和保存中关闭保护，失败后恢复并展示保存结果', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 1000 })
  await page.goto('/materials?kind=JD')
  await page.getByLabel('搜索资料名称或手工正文').fill('不匹配新资料')
  await page.getByRole('button', { name: '新增资料', exact: true }).click()
  await page.getByRole('menuitem', { name: '新增目标 JD' }).click()
  const dialog = page.getByRole('dialog')
  expect(await dialog.locator('#material-name').evaluate(element => getComputedStyle(element).fontSize)).toBe('16px')
  expect((await dialog.getByRole('button', { name: '保存资料', exact: true }).boundingBox())!.height).toBeGreaterThanOrEqual(44)
  await dialog.getByLabel('资料名称', { exact: true }).fill('合成验收目标岗位')
  await dialog.getByLabel('岗位要求').fill('合成要求：能够解释项目中的职责与约束。')
  let resolveSave!: () => void
  let writes = 0
  const gate = new Promise<void>(resolve => { resolveSave = resolve })
  await page.route('**/api/materials', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    writes++
    if (writes === 1) { await gate; return route.fulfill({ status: 500, json: { message: '合成保存失败，请重试' } }) }
    return route.continue()
  })
  await dialog.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(dialog.getByLabel('关闭弹窗')).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: '正在保存…', exact: true })).toBeDisabled()
  expect(writes).toBe(1)
  resolveSave()
  await expect(dialog.getByRole('alert')).toContainText('合成保存失败')
  await expect(dialog.getByLabel('岗位要求')).toHaveValue('合成要求：能够解释项目中的职责与约束。')
  await dialog.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '合成验收目标岗位', exact: true })).toContainText('资料已保存到本机')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByLabel('搜索资料名称或手工正文')).toHaveValue('')
  await expect(page.locator('.material-card').filter({ hasText: '合成验收目标岗位' })).toHaveCount(1)
  expect(writes).toBe(2)
})
