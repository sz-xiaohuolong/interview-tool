import { test, expect } from '@playwright/test'
import type { Session } from '../src/types'
import type { InterviewSession } from '../src/models/interview'

const longAnswer = '第一段：这是合成回放验收回答，我负责接口开发与异常处理。\n\n'
  + '第二段：通过事务、幂等请求和日志验证异常边界。'.repeat(45)
  + '\n\n第三段：保留原始长路径 ' + 'VeryLongProjectPathWithoutSpaces/'.repeat(20)

for (const width of [1440, 390]) {
  test(`旧版面试回放：${width}px 原始对话、左右气泡、刷新与只读返回`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = [], writes: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    const created = await page.request.post('/api/sessions', { data: { mode: 'QUICK', durationMinutes: 5, role: `合成回放面试 ${width}` } })
    expect(created.ok()).toBe(true)
    const initial = await created.json() as Session
    const answered = await page.request.post(`/api/sessions/${initial.id}/answers`, { data: {
      content: longAnswer, requestId: crypto.randomUUID(), expectedMessageId: initial.messages.at(-1)!.id,
    } })
    expect(answered.ok()).toBe(true)
    const finished = await page.request.post(`/api/sessions/${initial.id}/finish`, { data: {} })
    expect(finished.ok()).toBe(true)
    const original = await finished.json() as Session
    page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/') && request.method() !== 'GET') writes.push(request.url()) })
    await page.goto('/history')
    const row = page.locator('.history-row').filter({ hasText: `合成回放面试 ${width}` })
    const replay = row.getByRole('link', { name: '回放', exact: true })
    const report = row.getByRole('link', { name: '查看复盘', exact: true })
    await expect(replay).toBeVisible()
    const replayBox = (await replay.boundingBox())!, reportBox = (await report.boundingBox())!
    expect(replayBox.x + replayBox.width).toBeLessThanOrEqual(reportBox.x)
    await page.screenshot({ path: testInfo.outputPath('history-replay.png'), fullPage: true })
    await replay.click()
    await expect(page).toHaveURL(`/history/${initial.id}/replay`)
    await expect(page.getByRole('heading', { name: '对话回放', exact: true })).toBeVisible()
    const messages = page.locator('.message-content')
    await expect(messages).toHaveCount(original.messages.length)
    expect(await messages.allTextContents()).toEqual(original.messages.map(message => message.content))
    await expect(page.locator('textarea, form')).toHaveCount(0)
    expect(await page.locator('.message-content').first().evaluate(node => getComputedStyle(node).whiteSpace)).toBe('pre-wrap')
    expect(await page.locator('.message.user').evaluate(node => getComputedStyle(node).flexDirection)).toBe('row-reverse')
    await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBe(width)
    await page.screenshot({ path: testInfo.outputPath('conversation-replay.png'), fullPage: true })
    await page.reload()
    await expect(messages).toHaveCount(original.messages.length)
    await page.getByRole('link', { name: '返回面试历史' }).click()
    await expect(page).toHaveURL('/history')
    await expect(row).toBeVisible()
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}

test('综合面试回放读取已保存的完整问答，不进入工作台或创建资源', async ({ page }, testInfo) => {
  const created = await page.request.post('/api/interviews', { data: { role: '合成综合回放面试', level: 'JUNIOR', requestId: crypto.randomUUID() } })
  expect(created.ok()).toBe(true)
  let session = await created.json() as InterviewSession
  const url = `/api/interviews/${session.id}`
  async function waitFor(status: string) {
    await expect.poll(async () => { session = await (await page.request.get(url)).json(); return session.status }).toBe(status)
  }
  await waitFor('READY')
  const confirmed = await page.request.post(`${url}/confirm`, { data: { requestId: crypto.randomUUID(), expectedRevision: session.revision } })
  expect(confirmed.ok()).toBe(true)
  session = await confirmed.json()
  const answered = await page.request.post(`${url}/answers`, { data: {
    requestId: crypto.randomUUID(), expectedRevision: session.revision,
    expectedQuestionId: session.messages.at(-1)!.questionId, content: longAnswer,
  } })
  expect(answered.ok()).toBe(true)
  await waitFor('IN_PROGRESS')
  expect((await page.request.post(`${url}/finish`, { data: { requestId: crypto.randomUUID(), expectedRevision: session.revision } })).ok()).toBe(true)
  await waitFor('REPORT_READY')
  const writes: string[] = []
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/') && request.method() !== 'GET') writes.push(request.url()) })
  await page.goto('/history')
  const row = page.locator('.history-row').filter({ hasText: '合成综合回放面试' })
  await row.getByRole('link', { name: '回放', exact: true }).click()
  await expect(page).toHaveURL(`/history/interviews/${session.id}/replay`)
  await expect(page.getByRole('heading', { name: '合成综合回放面试', exact: true })).toBeVisible()
  expect(await page.locator('.message-content').allTextContents()).toEqual(session.messages.map(message => message.content))
  await expect(page.getByRole('link', { name: '查看复盘', exact: true })).toHaveAttribute('href', `/interviews/${session.id}`)
  await expect(page.locator('textarea, form')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('interview-conversation-replay.png'), fullPage: true })
  await page.getByRole('link', { name: '返回面试历史' }).click()
  await expect(row).toBeVisible()
  expect(writes).toEqual([])
})
