import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

async function post(api: APIRequestContext, path: string, data: unknown) {
  const response = await api.post(`/api${path}`, { data })
  expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy()
  return response.json()
}
async function tabTo(page: Page, target: Locator) {
  await expect(target).toBeVisible()
  for (let i = 0; i < 100; i++) {
    if (await target.evaluate(element => element === document.activeElement)) break
    await page.keyboard.press('Tab')
  }
  await expect(target).toBeFocused()
  const outline = await target.evaluate(element => Number.parseFloat(getComputedStyle(element).outlineWidth))
  expect(outline).toBeGreaterThanOrEqual(2)
}
async function reflowEvidence(page: Page, name: string) {
  // 720×500 CSS 视口模拟 1440×1000 在 200% 缩放时的可用布局；不冒称浏览器真实缩放。
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(720)
  await mkdir(resolve('../.evidence/v2/screens'), { recursive: true })
  await page.screenshot({ path: resolve(`../.evidence/v2/screens/keyboard-${name}-720.png`), fullPage: true, animations: 'disabled' })
}
test.use({ viewport: { width: 720, height: 500 } })

test('专项训练 自动准备作答后键盘 Enter 换行和 Space 保存可刷新', async ({ page, request }) => {
  const source = await post(request, '/sessions', { durationMinutes: 5 })
  await post(request, `/sessions/${source.id}/answers`, { requestId: crypto.randomUUID(), expectedMessageId: source.messages[0].id, content: '合成原回答：本地事务负责原子提交。' })
  await post(request, `/sessions/${source.id}/finish`, {})
  const c = await post(request, '/v2/practice-cases', { requestId: crypto.randomUUID(), sourceKind: 'SESSION', sourceId: source.id, messageId: source.messages[0].id })
  const writes: { path: string; body: any }[] = []
  page.on('request', req => { if (['POST', 'PUT'].includes(req.method()) && req.url().includes('/api/training/')) writes.push({ path: new URL(req.url()).pathname, body: req.postDataJSON() }) })
  await page.goto(`/practice/${c.id}`)
  const answer = page.getByLabel('我的新回答', { exact: true })
  await tabTo(page, answer); await page.keyboard.insertText('合成第一段：事务保证本地原子性。'); await page.keyboard.press('Enter'); await page.keyboard.insertText('合成第二段：外部支付仍需幂等。')
  expect(writes.filter(w => /\/(draft|submit)$/.test(w.path))).toHaveLength(0)
  const save = page.getByRole('button', { name: '暂存草稿', exact: true })
  await tabTo(page, save); await page.keyboard.press('Space')
  await expect(page.getByText('草稿已明确保存到本机服务。', { exact: true })).toBeVisible()
  expect(writes.filter(w => w.path.endsWith('/attempts'))).toHaveLength(1)
  const draft = writes.find(w => w.path.endsWith('/draft'))!
  expect(draft.body.answer).toBe('合成第一段：事务保证本地原子性。\n合成第二段：外部支付仍需幂等。')
  await expect(save).toBeFocused(); await reflowEvidence(page, 'practice')
  await page.reload()
  await expect(answer).toHaveValue(draft.body.answer)
  expect(writes.filter(w => w.path.endsWith('/attempts'))).toHaveLength(1)
})

test('专项训练 JD 用键盘选择材料、只预览快照并展开来源', async ({ page, request }) => {
  const name = `合成键盘材料-${crypto.randomUUID().slice(0, 8)}`
  await post(request, '/materials', { kind: 'PROJECT', name, content: '个人负责订单事务与幂等；容量未知。' })
  const writes: string[] = []
  page.on('request', req => { if (req.method() === 'POST') writes.push(new URL(req.url()).pathname) })
  await page.goto('/jd-diagnostics')
  await tabTo(page, page.getByLabel('岗位描述 JD', { exact: true })); await page.keyboard.insertText('熟悉 Java 事务与接口幂等。')
  const material = page.getByRole('checkbox', { name: `${name} · 项目`, exact: true })
  await tabTo(page, material); await page.keyboard.press('Space'); await expect(material).toBeChecked()
  await tabTo(page, page.getByRole('button', { name: '预览本次模型输入（不调用模型）', exact: true })); await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '本次将发送的文本', exact: true })).toBeVisible()
  const summary = page.locator('.training-preview summary').filter({ hasText: '资料原文' }).first()
  await tabTo(page, summary); await page.keyboard.press('Space')
  await expect(summary.locator('..')).toHaveAttribute('open', '')
  await expect(summary.locator('..').getByText('个人负责订单事务与幂等；容量未知。', { exact: true })).toBeVisible()
  expect(writes).toHaveLength(1); expect(writes).not.toContain('/api/training/jd-diagnostics')
  await reflowEvidence(page, 'jd-preview')
})

test('专项训练 阶段训练键盘保存后恢复焦点，确认推进后聚焦下一阶段', async ({ page }) => {
  await page.goto('/design-practice')
  await tabTo(page, page.getByRole('button', { name: '开始五阶段训练', exact: true })); await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '需求澄清', exact: true })).toBeVisible()
  const areas = page.locator('textarea[id^="field-"]'); await expect(areas.first()).toBeEditable()
  for (let i = 0; i < await areas.count(); i++) { await tabTo(page, areas.nth(i)); await page.keyboard.insertText(`合成需求 ${i + 1}：明确边界，未知数值留待验证。`) }
  const save = page.getByRole('button', { name: '保存阶段草稿', exact: true })
  await tabTo(page, save); await page.keyboard.press('Space')
  await expect(page.getByText('草稿已保存到本机服务。', { exact: true })).toBeVisible(); await expect(save).toBeFocused()
  await tabTo(page, page.getByRole('button', { name: '提交产物并调用模型反馈', exact: true })); await page.keyboard.press('Enter')
  const acknowledge = page.getByLabel('我已核对，接受带未解决项继续', { exact: true })
  await tabTo(page, acknowledge); await page.keyboard.press('Space'); await expect(acknowledge).toBeChecked()
  await tabTo(page, page.getByRole('button', { name: '确认进入下一阶段', exact: true })); await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '约束与容量', exact: true })).toBeVisible(); await expect(areas.first()).toBeFocused(); await expect(areas.first()).toBeEditable()
  await reflowEvidence(page, 'design')
})
