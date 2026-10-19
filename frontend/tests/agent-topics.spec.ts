import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { AgentPracticeOptions, InterviewSession } from '../src/models/interview'

// 直连验收环境的显式 Mock 服务，只验证接线、状态和展示，不评价模型专业性。
const agentRole = '大模型应用 / Agent 开发工程师'

async function noHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth)).toBeLessThanOrEqual(1)
}

async function noInternalHints(page: Page) {
  await expect(page.locator('[data-topic-coverage], [data-topic-point], [data-followup-diagnostics]')).toHaveCount(0)
  await expect(page.locator('[data-action="confirm"], [aria-label="面试四阶段"]')).toHaveCount(0)
}

test('Agent 默认选岗位和题量直接开始，不请求复习预检或资料', async ({ page }) => {
  const requests: { path: string; method: string }[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/')) requests.push({ path, method: request.method() })
  })
  await page.goto('/configure/quick')
  await page.locator('#quick-role').selectOption(agentRole)
  await page.locator('#quick-question-count').selectOption('3')
  await expect(page.locator('.quick-level')).toHaveJSProperty('open', false)
  const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
  const response = await created
  expect(response.ok()).toBe(true)
  expect(response.request().postDataJSON()).toEqual(expect.objectContaining({ sessionType: 'QUICK', role: agentRole, plannedQuestionCount: 3, agentTopics: [], practicePreference: 'NEW_FIRST' }))
  const initial = await response.json() as InterviewSession
  expect(initial.mock).toBe(true)
  expect(initial).not.toHaveProperty('agentPractice')
  await expect(page).toHaveURL(`/interviews/${encodeURIComponent(initial.id)}`)
  await expect(page.locator('#interview-answer')).toBeEnabled()
  await noInternalHints(page)
  expect(requests.filter(request => request.path === '/api/interviews/agent-practice-options' || request.path === '/api/materials' || /^\/api\/sessions(?:\/|$)/.test(request.path))).toEqual([])
})

for (const width of [390, 320]) {
  test(`${width}px 单主题三道主问题连续完成、考点回顾定位且无横向溢出`, async ({ page }, testInfo) => {
    test.setTimeout(60_000)
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto('/configure/quick')
    await page.locator('#quick-role').selectOption(agentRole)
    await page.locator('#quick-question-count').selectOption('3')
    await page.locator('.quick-level > summary').click()
    await expect(page.locator('.quick-level')).toHaveJSProperty('open', true)
    await page.locator('[name="agentTopics"][value="AGENT_CONTEXT_MEMORY"]').check()
    await expect(page.locator('[name="agentTopics"]:checked')).toHaveCount(1)
    await noHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath(`agent-topics-configuration-${width}.png`), fullPage: true })

    const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
    const createdResponse = await created
    expect(createdResponse.ok()).toBe(true)
    expect(createdResponse.request().postDataJSON()).toEqual(expect.objectContaining({ agentTopics: ['AGENT_CONTEXT_MEMORY'], plannedQuestionCount: 3, practicePreference: 'NEW_FIRST' }))
    const initial = await createdResponse.json() as InterviewSession
    expect(initial.mock).toBe(true)
    const apiPath = `/api/interviews/${encodeURIComponent(initial.id)}`
    await expect(page).toHaveURL(`/interviews/${encodeURIComponent(initial.id)}`)
    await expect(page.locator('#interview-answer')).toBeEnabled()
    await noInternalHints(page)

    for (let index = 0; index < 9; index++) {
      await page.locator('#interview-answer').fill(`合成回答 ${index + 1}：我先保留当前任务约束，区分用户原话与临时观察，再用固定对话检查状态恢复；当前没有真实上线指标。`)
      const submitted = page.waitForResponse(response => new URL(response.url()).pathname === `${apiPath}/answers` && response.request().method() === 'POST')
      await page.locator('[data-action="answer"]').click()
      expect((await submitted).ok()).toBe(true)
      await expect(page.locator('.interview-dialogue .message.user')).toHaveCount(index + 1)
      await expect(page.locator('[data-thinking]')).toHaveCount(0)
      await noInternalHints(page)
      if (index < 8) await expect(page.locator('#interview-answer')).toBeEnabled()
    }
    await expect(page.locator('#interview-answer')).toHaveCount(0)
    await expect(page.locator('[data-action="finish"]')).toHaveText('生成查证复盘')
    const formalText = await page.locator('.interview-page').innerText()
    const finished = page.waitForResponse(response => new URL(response.url()).pathname === `${apiPath}/finish` && response.request().method() === 'POST')
    await page.locator('[data-action="finish"]').click()
    expect((await finished).ok()).toBe(true)
    await expect(page.locator('[data-topic-coverage]')).toBeVisible()

    const savedResponse = await page.request.get(apiPath)
    expect(savedResponse.ok()).toBe(true)
    const saved = await savedResponse.json() as InterviewSession
    expect(saved.status).toBe('REPORT_READY')
    const coverage = saved.report?.topicCoverage
    expect(coverage).toEqual(expect.objectContaining({ askedCount: 3, answeredCount: 3, newCount: 3, reviewCount: 0 }))
    expect(coverage!.items).toHaveLength(3)
    expect(new Set(coverage!.items.map(item => item.pointId)).size).toBe(3)
    expect(coverage!.items.every(item => item.theme === 'AGENT_CONTEXT_MEMORY' && item.answered && !item.review)).toBe(true)
    const section = page.locator('[data-topic-coverage]')
    await expect(section.locator('[data-topic-point]')).toHaveCount(3)
    await expect(section).toContainText('上下文与记忆')
    await expect(section).toContainText('新练 3')
    for (const item of coverage!.items) {
      expect(formalText).not.toContain(item.pointId)
      await expect(section).toContainText(item.title)
      const question = saved.messages.find(message => message.role === 'ASSISTANT' && message.questionId === item.questionId)
      expect(question).toBeDefined()
      await section.locator(`[data-topic-locate="${item.questionId}"]`).click()
      const original = page.locator(`[data-answer="${item.questionId}"]`)
      await expect(original).toBeVisible()
      await expect(original).toBeFocused()
      await expect(original).toContainText(question!.content)
      await expect(page.locator('[data-question-group]').filter({ has: original })).toHaveJSProperty('open', true)
    }
    await noHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath(`agent-topics-report-${width}.png`), fullPage: true })
    expect(errors).toEqual([])
  })
}

test('REVIEW 空池经过真实预检阻止创建，明确切换偏好不会自动开场', async ({ page }) => {
  const creates: string[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/interviews' && request.method() === 'POST') creates.push(request.url())
  })
  await page.goto('/configure/quick')
  await page.locator('#quick-role').selectOption(agentRole)
  await page.locator('#quick-question-count').selectOption('3')
  await page.locator('.quick-level > summary').click()
  await page.locator('#quick-practice-preference').selectOption('REVIEW')
  const checked = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews/agent-practice-options' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
  const response = await checked
  expect(response.ok()).toBe(true)
  expect(response.request().postDataJSON()).toEqual({ role: agentRole, level: 'JUNIOR', plannedQuestionCount: 3, agentTopics: [] })
  const options = await response.json() as AgentPracticeOptions
  // Mock 场次不进入有效练习池，因此不受其他浏览器用例的合成场次影响。
  expect(options).toEqual(expect.objectContaining({ eligible: true, reviewAvailable: 0, canStart: false }))
  await expect(page).toHaveURL('/configure/quick')
  await expect(page.locator('[data-review-options]')).toContainText(options.message)
  await expect(page.locator('[data-switch-new-first]')).toBeVisible()
  expect(creates).toEqual([])
  await page.locator('[data-switch-new-first]').click()
  await expect(page.locator('#quick-practice-preference')).toHaveValue('NEW_FIRST')
  await expect(page.locator('[data-review-options]')).toHaveCount(0)
  expect(creates).toEqual([])
})
