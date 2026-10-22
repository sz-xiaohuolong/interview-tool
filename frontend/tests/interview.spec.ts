import { test as base, expect, type Page, type TestInfo } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const evidenceDir = resolve(dirname(fileURLToPath(import.meta.url)), '../../.evidence')
const test = base.extend<{ browserErrors: string[] }>({
  browserErrors: [async ({ page }, use, testInfo) => {
    const errors: string[] = []
    page.on('console', message => { if (message.type() === 'error') errors.push(`控制台错误：${message.text()}`) })
    page.on('pageerror', error => errors.push(`页面异常：${error.message}`))
    await use(errors)
    if (errors.length) await testInfo.attach('浏览器错误', { body: errors.join('\n'), contentType: 'text/plain' })
    expect(errors, '浏览器不应出现运行错误').toEqual([])
  }, { auto: true }],
})

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  await mkdir(evidenceDir, { recursive: true })
  const path = resolve(evidenceDir, name)
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  await testInfo.attach(name, { path, contentType: 'image/png' })
}

async function expectNoOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => ({
    content: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
    viewport: window.innerWidth,
  })), { message: `${new URL(page.url()).pathname} 在 390px 视口不应横向溢出` }).toEqual({ content: 390, viewport: 390 })
}

async function finishInterview(page: Page) {
  await page.getByRole('button', { name: '结束并复盘', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认结束并复盘' }).click()
  await expect(page).toHaveURL(/\/reports\/[^/]+$/)
  await expect(page.getByRole('heading', { name: '四个维度，看清表现' })).toBeVisible()
}

test('旧版场次兼容：多行回答、刷新恢复、报告、历史与专项练习入口', async ({ page }, testInfo) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '把你会的，讲得更好。' })).toBeVisible()
  await expect(page.getByText('当前为 Mock 测试模式：', { exact: false })).toBeVisible()
  await screenshot(page, testInfo, 'e2e-home.png')
  const created = await page.request.post('/api/sessions', { data: { mode: 'QUICK', role: 'Java 后端工程师', durationMinutes: 5 } })
  expect(created.ok()).toBe(true)
  await page.goto(`/sessions/${(await created.json()).id}`)
  await expect(page).toHaveURL(/\/sessions\/[^/]+$/)
  const sessionId = new URL(page.url()).pathname.split('/').pop()!
  const answer = page.getByLabel('你的回答', { exact: true })
  await expect(answer).toBeEnabled()
  const firstAnswer = '合成验收回答：我负责请求处理与持久化，使用事务保证写入一致性。'
  await answer.fill(firstAnswer)
  await page.getByRole('button', { name: '发送回答' }).click()
  await expect(page.getByLabel('面试对话记录')).toContainText(firstAnswer)
  await expect(answer).toBeEnabled()
  const secondAnswer = '合成验收第一段：通过唯一请求标识避免重复写入。\n合成验收第二段：异常时回滚事务，并记录定位依据。'
  await answer.fill(secondAnswer)
  await page.reload()
  await expect(page.getByLabel('面试对话记录')).toContainText(firstAnswer)
  await expect(answer).toHaveValue(secondAnswer)
  await expect(answer).toBeEnabled()
  await answer.press('Control+Enter')
  await expect(page.getByLabel('面试对话记录')).toContainText(secondAnswer)
  await expect(answer).toHaveValue('')
  await page.reload()
  await expect(page.getByLabel('面试对话记录')).toContainText(secondAnswer)
  await finishInterview(page)
  await expect(page.getByText('这是一份 Mock 测试报告。', { exact: false })).toBeVisible()
  await expect(page.locator('.question-analysis')).toContainText(firstAnswer)
  await expect(page.locator('.question-analysis')).toContainText(secondAnswer)
  await screenshot(page, testInfo, 'e2e-report.png')
  await page.getByRole('link', { name: '返回历史', exact: true }).click()
  await page.getByRole('button', { name: '已完成', exact: true }).click()
  const historyEntry = page.locator(`a.history-entry[href="/reports/${sessionId}"]`)
  await expect(historyEntry).toContainText('已回答 2 次')
  await historyEntry.click()
  await page.getByRole('link', { name: '专项练习', exact: true }).first().click()
  await expect(page).toHaveURL(/\/interviews\?focus=/)
  await expect(page.locator('[data-action="prepare"]')).toBeVisible()
})

test('简历：页面上传 Markdown、保存十道押题并从单题进入面试', async ({ page }) => {
  await page.goto('/materials')
  await page.getByRole('button', { name: '新增资料', exact: true }).click()
  await page.getByRole('menuitem', { name: '新增简历', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('资料名称（选填）').fill('合成验收简历')
  await dialog.getByLabel('选择简历文件').setInputFiles({
    name: '合成验收简历.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# 合成测试资料\n此文档不包含个人信息。\n项目经历：开发本地知识库服务。\n技术栈：Java、Spring Boot、SQLite。\n个人职责：接口设计、异常处理与自动化测试。'),
  })
  await dialog.getByRole('button', { name: '上传并保存', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '合成验收简历', exact: true })
  await expect(dialog).toContainText('个人职责：接口设计、异常处理与自动化测试。')
  await dialog.getByRole('link', { name: '开始简历押题' }).click()
  await expect(page.getByLabel('选择简历')).toHaveValue(/.+/)
  await page.getByRole('button', { name: '生成 10 道针对性押题', exact: true }).click()
  const questions = page.getByRole('button', { name: '练习这道题', exact: true })
  await expect(questions).toHaveCount(10)
  await expect(page.getByText('Mock 测试结果', { exact: true })).toBeVisible()
  await page.reload()
  await expect(questions).toHaveCount(10)
  const question = await page.locator('.prediction-list article').first().getByRole('heading').innerText()
  await questions.first().click()
  await expect(page).toHaveURL(/\/sessions\/[^/]+$/)
  await expect(page.getByLabel('面试对话记录')).toContainText(question)
  await expect(page.getByLabel('你的回答')).toBeEnabled()
})

test('项目：创建文字资料、上传 TXT 附件并进入项目面试', async ({ page }) => {
  await page.goto('/materials')
  await page.getByRole('group', { name: '资料分类' }).getByRole('button', { name: /^项目/ }).click()
  await page.getByRole('button', { name: '新增资料', exact: true }).click()
  await page.getByRole('menuitem', { name: '新增项目', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('项目名称').fill('合成验收知识库项目')
  const content = '项目简介：本地知识库练习服务。\n技术栈：Java、SQLite。\n个人职责：设计资料接口、处理附件与异常。'
  await dialog.getByLabel('项目简介、技术栈与个人职责').fill(content)
  await dialog.getByRole('button', { name: '保存资料', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '合成验收知识库项目', exact: true })
  await expect(dialog).toContainText(content)
  const attachment = '合成项目附件：一次资料请求依次完成格式校验、文本提取和事务保存；失败时返回明确错误。'
  await dialog.getByLabel('添加附件（可多选）').setInputFiles({ name: '合成流程说明.txt', mimeType: 'text/plain', buffer: Buffer.from(attachment) })
  await dialog.getByRole('button', { name: '上传附件', exact: true }).click()
  await expect(dialog).toContainText(attachment)
  await dialog.getByRole('link', { name: '开始项目面试' }).click()
  await expect(page.getByLabel('选择项目')).toHaveValue(/.+/)
  await expect(page.getByText('1 份附件 · 使用当前资料创建本场快照')).toBeVisible()
  await page.getByRole('button', { name: '开始面试', exact: true }).click()
  await expect(page).toHaveURL(/\/sessions\/[^/]+$/)
  await expect(page.getByLabel('面试对话记录')).toContainText('请结合项目资料')
  await expect(page.getByLabel('你的回答')).toBeEnabled()
})

test('390px 窄屏：首页、配置、资料、历史、对话与报告没有横向溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  for (const path of ['/', '/configure/quick', '/materials', '/history']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('.loading-state')).toHaveCount(0)
    await expectNoOverflow(page)
  }
  await page.goto('/materials')
  await page.getByRole('button', { name: '新增资料', exact: true }).click()
  await page.getByRole('menuitem', { name: '新增简历', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expectNoOverflow(page)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  const created = await page.request.post('/api/sessions', { data: { mode: 'QUICK', role: 'Java 后端工程师', durationMinutes: 5 } })
  expect(created.ok()).toBe(true)
  await page.goto(`/sessions/${(await created.json()).id}`)
  await expect(page.getByLabel('你的回答')).toBeEnabled()
  await expectNoOverflow(page)
  await page.getByLabel('你的回答').fill('窄屏合成验收回答：通过失败用例验证接口边界。')
  await page.getByRole('button', { name: '发送回答' }).click()
  await expect(page.getByLabel('你的回答')).toHaveValue('')
  await finishInterview(page)
  await expectNoOverflow(page)
  await screenshot(page, testInfo, 'e2e-report-mobile.png')
})
