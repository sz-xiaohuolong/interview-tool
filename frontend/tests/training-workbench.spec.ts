import { test, expect, type APIRequestContext } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
async function post(api: APIRequestContext, path: string, data: unknown) {
  const r = await api.post(`/api${path}`, { data }); expect(r.ok(), await r.text()).toBeTruthy(); return r.json()
}
async function fixture(api: APIRequestContext, answerFollowUp = false) {
  let s = await post(api, '/sessions', { durationMinutes: 5 })
  s = await post(api, `/sessions/${s.id}/answers`, { content: '合成回答：用事务与唯一约束处理幂等，失败回滚。', requestId: crypto.randomUUID(), expectedMessageId: s.messages[0].id })
  if (answerFollowUp) s = await post(api, `/sessions/${s.id}/answers`, { content: '合成追问回答：记录请求状态，失败补偿并核对最终结果。', requestId: crypto.randomUUID(), expectedMessageId: s.messages[2].id })
  s = await post(api, `/sessions/${s.id}/finish`, {})
  const report = await (await api.get(`/api/sessions/${s.id}/report`)).json()
  const preview = await (await api.get(`/api/training/review-import-preview?sessionId=${s.id}`)).json()
  const review = (await post(api, '/training/review-imports', { requestId: crypto.randomUUID(), sessionId: s.id, expectedReportHash: preview.reportHash })).items[0]
  return { s, report, review }
}
test('合并工作台：选题保留草稿、取消及确认删除、旧链接与原报告保护', async ({ page, request }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  const a = await fixture(request), b = await fixture(request)
  await page.goto(`/review/${a.review.id}`)
  await expect(page.locator('nav[aria-label="持续训练"] a')).toHaveText(['错题重练', '项目经历'])
  await expect(page.getByLabel('我的新回答', { exact: true })).toBeEditable()
  const casePath = new URL(page.url()).pathname
  await page.getByLabel('我的新回答', { exact: true }).fill('切题后应保留的合成草稿：先检查幂等键。')
  await page.locator(`[data-workbench-open="review:${b.review.id}"]`).click()
  await page.locator(`[data-workbench-open="review:${a.review.id}"]`).click()
  await expect(page.getByLabel('我的新回答', { exact: true })).toHaveValue(/切题后应保留/)
  const out = resolve('../.runtime/training-workbench-20261021'); await mkdir(out, { recursive: true })
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: resolve(out, `workbench-answer-${width}.png`), fullPage: true })
  }
  await page.getByRole('button', { name: '返回题目列表', exact: true }).click()
  await page.screenshot({ path: resolve(out, 'workbench-list-320.png'), fullPage: true })
  page.once('dialog', d => d.dismiss())
  await page.locator(`[data-workbench-delete="review:${a.review.id}"]`).click()
  await expect(page.locator(`[data-workbench-row="review:${a.review.id}"]`)).toHaveCount(1)
  page.once('dialog', d => d.accept())
  await page.locator(`[data-workbench-delete="review:${a.review.id}"]`).click()
  await expect(page.locator(`[data-workbench-row="review:${a.review.id}"]`)).toHaveCount(0)
  expect((await request.get(`/api/training/practice-cases/${casePath.split('/').at(-1)}`)).status()).toBe(404)
  await page.goto(casePath); await expect(page.getByRole('alert')).toContainText('记录已删除')
  expect(await (await request.get(`/api/sessions/${a.s.id}/report`)).json()).toEqual(a.report)
  expect((await (await request.get(`/api/sessions/${a.s.id}`)).json()).messages).toEqual(a.s.messages)
  expect(errors).toEqual([])
})
test('未导入复习的独立原题也可在列表删除，旧作答地址失效', async ({ page, request }) => {
  const { s, report } = await fixture(request)
  const c = await post(request, '/training/practice-cases', { requestId: crypto.randomUUID(), sourceKind: 'SESSION', sourceId: s.id, messageId: s.messages[0].id })
  // 创建第二个未导入复习的会话，检验独立案例入口。
  let independent = await post(request, '/sessions', { durationMinutes: 5 })
  independent = await post(request, `/sessions/${independent.id}/answers`, { requestId: crypto.randomUUID(), expectedMessageId: independent.messages[0].id, content: '合成回答：说明原子提交。' })
  await post(request, `/sessions/${independent.id}/finish`, {})
  const solo = await post(request, '/training/practice-cases', { requestId: crypto.randomUUID(), sourceKind: 'SESSION', sourceId: independent.id, messageId: independent.messages[0].id })
  await page.goto('/practice')
  await expect(page.locator(`[data-workbench-row="case:${solo.id}"]`)).toBeVisible()
  page.once('dialog', d => d.accept())
  await page.locator(`[data-workbench-delete="case:${solo.id}"]`).click()
  await expect(page.locator(`[data-workbench-row="case:${solo.id}"]`)).toHaveCount(0)
  expect((await request.get(`/api/training/practice-cases/${c.id}`)).ok()).toBeTruthy()
  expect(await (await request.get(`/api/sessions/${s.id}/report`)).json()).toEqual(report)
})

test('手机从详情返回题目列表时恢复原来的滚动位置和筛选', async ({ page, request }) => {
  for (let i = 0; i < 6; i++) await fixture(request)
  await page.setViewportSize({ width: 390, height: 650 })
  await page.goto('/review')
  await page.getByLabel('搜索题目或来源', { exact: true }).fill('面试复习')
  const target = page.locator('[data-workbench-open]').last()
  await target.scrollIntoViewIfNeeded()
  const position = await page.evaluate(() => window.scrollY)
  expect(position).toBeGreaterThan(300)
  await target.click()
  await expect(page.locator('[data-workbench-content]')).toBeVisible()
  await page.getByRole('button', { name: '返回题目列表', exact: true }).click()
  await expect(page.getByLabel('搜索题目或来源', { exact: true })).toHaveValue('面试复习')
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeCloseTo(position, -1)
})

test('整题合并后可继续原有追问，保留原尝试且不重复创建', async ({ page, request }) => {
  const { s, review } = await fixture(request, true)
  const c = await post(request, '/training/practice-cases', { requestId: crypto.randomUUID(), sourceKind: 'SESSION', sourceId: s.id, messageId: s.messages[2].id })
  const attempt = await post(request, `/training/practice-cases/${c.id}/attempts`, { requestId: crypto.randomUUID(), exposureDeclaration: 'UNKNOWN', answer: '原追问草稿：核对最终结果。' })
  const writes: string[] = []
  page.on('request', req => { if (['POST', 'PUT', 'DELETE'].includes(req.method())) writes.push(new URL(req.url()).pathname) })
  await page.goto(`/review/${review.id}`)
  await page.locator(`[data-workbench-row="review:${review.id}"]`).getByText('同题作答记录（1）', { exact: true }).click()
  await page.locator(`[data-workbench-case="${c.id}"]`).click()
  await expect(page).toHaveURL(new RegExp(`/practice/${c.id}\\?attemptId=${attempt.id}`))
  await expect(page.getByLabel('我的新回答', { exact: true })).toHaveValue('原追问草稿：核对最终结果。')
  expect(writes).toEqual([])
})


test('项目经历保留：STAR和自我介绍确认版均进入同一重练工作台', async ({ page, request }) => {
  for (const kind of ['STAR', 'INTRO']) {
    const keys = kind === 'STAR' ? ['situation', 'task', 'action', 'result', 'reflection'] : ['targetRole', 'background', 'experience', 'contribution', 'aspiration']
    const fields = Object.fromEntries(keys.map(key => [key, { text: `本人合成声明：${key}，个人实现订单幂等，不编造业绩数字。`, originType: 'USER_ASSERTION' }]))
    let story = await post(request, '/training/stories', { requestId: crypto.randomUUID(), templateKind: kind, fields })
    story = await post(request, `/training/stories/${story.id}/confirm`, { requestId: crypto.randomUUID(), expectedVersion: story.revision, storyVersion: story.currentVersion, confirmations: Object.fromEntries(keys.map(key => [key, { confirmed: true }])) })
    await page.goto(`/stories/${story.id}`)
    await expect(page.locator('button.practice-cta')).toBeVisible()
    await page.locator('button.practice-cta').click()
    await expect(page.getByLabel('我的新回答', { exact: true })).toBeEditable()
    await expect(page.locator('[data-workbench-list]')).toBeVisible()
    await page.getByLabel('我的新回答', { exact: true }).fill('合成练习：我负责订单幂等校验，用唯一约束和事务保证一致性；结果缺少量化数据，后续补证。')
    await page.getByRole('button', { name: '提交并对比', exact: true }).click()
    await expect(page.getByLabel('我的新回答', { exact: true })).toHaveAttribute('readonly', '')
    await expect(page.getByText('评估完成', { exact: true })).toBeVisible()
  }
})
