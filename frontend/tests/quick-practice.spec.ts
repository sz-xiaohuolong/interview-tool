import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { InterviewSession } from '../src/models/interview'

// 使用验收环境显式配置的 Mock 后端，只验证流程与数据关联，不评价真实模型专业性。
const scenarios = [
  { name: '桌面答满题量', width: 1440, height: 1000, role: 'Java 后端工程师', planned: 3, answerCount: 9, completed: true },
  { name: '手机提前结束', width: 390, height: 844, role: '大模型应用 / Agent 开发工程师', planned: 8, answerCount: 2, completed: false },
]

async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth)).toBeLessThanOrEqual(1)
}

async function expectQuickWorkbench(page: Page) {
  await expect(page.locator('[data-action="confirm"], [aria-label="面试四阶段"], [aria-label="本场面试进度"], .interview-summary')).toHaveCount(0)
  await expect(page.locator('[data-followup-diagnostics]')).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: '主导航', exact: true }).locator('[aria-current="page"]')).toHaveText('快速练习')
}

for (const scenario of scenarios) {
  test(`快速练习${scenario.name}：自动首问、连续回答与刷新、复盘诊断、统一历史和回放`, async ({ page }, testInfo) => {
    test.setTimeout(60_000)
    await page.setViewportSize({ width: scenario.width, height: scenario.height })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto('/')
    await expect(page.locator('a.mode-card[href="/interviews"]')).toContainText('综合面试')
    await page.locator('a.mode-card[href="/configure/quick"]').click()
    await expect(page).toHaveURL('/configure/quick')
    await expect(page.locator('#quick-question-count')).toHaveValue('5')
    await expect(page.locator('#quick-level')).toHaveValue('JUNIOR')
    await expect(page.locator('.quick-level')).toHaveJSProperty('open', false)
    await expect(page.locator('.quick-practice-page textarea, .quick-practice-page input[type="file"]')).toHaveCount(0)
    await page.locator('#quick-role').selectOption(scenario.role)
    await page.locator('#quick-question-count').selectOption(String(scenario.planned))
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('quick-configuration.png'), fullPage: true })

    const requests: { path: string; method: string }[] = []
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (path.startsWith('/api/')) requests.push({ path, method: request.method() })
    })
    const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
    const createdResponse = await created
    expect(createdResponse.ok()).toBe(true)
    expect(createdResponse.request().postDataJSON()).toEqual(expect.objectContaining({
      sessionType: 'QUICK', role: scenario.role, level: 'JUNIOR', plannedQuestionCount: scenario.planned,
      resumeId: '', jdId: '', jd: '',
    }))
    const initial = await createdResponse.json() as InterviewSession
    const path = `/interviews/${encodeURIComponent(initial.id)}`
    const apiPath = `/api/interviews/${encodeURIComponent(initial.id)}`
    await expect(page).toHaveURL(path)
    await expect(page.locator('#interview-answer')).toBeEnabled()
    await expect(page.locator('.interview-dialogue .message:not(.user) .message-content').first()).toBeVisible()
    await expectQuickWorkbench(page)
    await expectNoHorizontalOverflow(page)

    const answers: string[] = []
    for (let index = 0; index < scenario.answerCount; index++) {
      const answer = `合成流程回答 ${index + 1}：我负责接口与异常处理，通过唯一请求编号识别重复提交，使用日志和失败场景检查处理结果。`
      answers.push(answer)
      await page.locator('#interview-answer').fill(answer)
      const submitted = page.waitForResponse(response => new URL(response.url()).pathname === `${apiPath}/answers` && response.request().method() === 'POST')
      await page.locator('[data-action="answer"]').click()
      expect((await submitted).ok()).toBe(true)
      await expect(page.locator('.interview-dialogue .message.user')).toHaveCount(index + 1)
      await expect(page.locator('[data-thinking]')).toHaveCount(0)
      if (scenario.completed && index === scenario.answerCount - 1) {
        await expect(page.locator('#interview-answer')).toHaveCount(0)
        await expect(page.locator('[data-action="finish"]')).toHaveText('生成查证复盘')
      } else {
        await expect(page.locator('#interview-answer')).toBeEnabled()
      }
      await expectQuickWorkbench(page)
      if (index === 1) {
        await page.reload()
        await expect(page).toHaveURL(path)
        await expect(page.locator('#interview-answer')).toBeEnabled()
        await expect(page.locator('.interview-dialogue .message.user')).toHaveCount(2)
        for (const submittedAnswer of answers) await expect(page.locator('.interview-dialogue')).toContainText(submittedAnswer)
        await expectQuickWorkbench(page)
        await expectNoHorizontalOverflow(page)
      }
    }
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('quick-conversation.png'), fullPage: true })
    await expect(page.locator('[data-action="finish"]')).toHaveText(scenario.completed ? '生成查证复盘' : '结束面试，查证复盘')
    const finished = page.waitForResponse(response => new URL(response.url()).pathname === `${apiPath}/finish` && response.request().method() === 'POST')
    await page.locator('[data-action="finish"]').click()
    expect((await finished).ok()).toBe(true)
    await expect(page.locator('[data-overview]')).toBeVisible()
    await expect(page.locator('[data-detailed-assessment]')).toBeVisible()
    await expect(page.locator('.review-meta')).toContainText(`${scenario.answerCount} 次作答`)
    await expect(page.locator('.review-meta')).toContainText(`原计划 ${scenario.planned} 道主问题`)
    await expect(page.locator('.review-meta')).toContainText(scenario.completed ? '3 道主问题、6 次追问 · 题目流程结束' : '主动结束')
    await expect(page.locator('[aria-label="本场四阶段完成情况"], .review-matrix')).toHaveCount(0)

    const savedResponse = await page.request.get(apiPath)
    expect(savedResponse.ok()).toBe(true)
    const saved = await savedResponse.json() as InterviewSession
    expect(saved).toEqual(expect.objectContaining({ sessionType: 'QUICK', plannedQuestionCount: scenario.planned, status: 'REPORT_READY' }))
    expect(saved.report?.overview).toEqual(expect.objectContaining({
      answeredCount: scenario.answerCount, endReason: scenario.completed ? 'QUESTIONS_COMPLETED' : 'USER_FINISHED',
    }))
    expect(saved.messages.filter(message => message.role === 'USER').map(message => message.content)).toEqual(answers)
    expect(saved.report?.diagnostics).toHaveLength(scenario.answerCount)
    const diagnostics = page.locator('[data-followup-diagnostics]')
    await expect(diagnostics).toHaveJSProperty('open', false)
    await diagnostics.locator('summary').click()
    await expect(diagnostics).toHaveJSProperty('open', true)
    await expect(diagnostics).toContainText('以下是当时的追问观察')
    const firstDiagnosis = saved.report!.diagnostics![0]
    const originalAnswer = saved.messages.find(message => message.id === firstDiagnosis.answerMessageId && message.role === 'USER')!
    expect(originalAnswer.questionId).toBe(firstDiagnosis.questionId)
    await expect(diagnostics.locator('blockquote').first()).toContainText(firstDiagnosis.diagnosis.answerQuote)
    await diagnostics.locator('[data-diagnosis-locate]').first().click()
    const locatedAnswer = page.locator(`[data-answer="${firstDiagnosis.questionId}"]`)
    await expect(locatedAnswer).toBeVisible()
    await expect(locatedAnswer).toBeFocused()
    await expect(locatedAnswer).toContainText(originalAnswer.content)
    await expect(page.locator('[data-question-group]').filter({ has: locatedAnswer })).toHaveJSProperty('open', true)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('quick-report-diagnostics.png'), fullPage: true })

    expect(requests.filter(request => /^\/api\/sessions(?:\/|$)/.test(request.path))).toEqual([])
    expect(requests.filter(request => request.path === '/api/materials' || request.path.endsWith('/confirm') || request.path.endsWith('/skip-stage'))).toEqual([])
    await page.getByRole('link', { name: '返回练习概览', exact: true }).click()
    await expect(page).toHaveURL('/')
    const recent = page.locator(`a.list-row[href="${path}"]`)
    await expect(recent).toContainText('快速练习')
    await expectNoHorizontalOverflow(page)
    await recent.click()
    await expect(page).toHaveURL(path)
    await expect(page.locator('[data-overview]')).toBeVisible()
    await expect(page.getByRole('navigation', { name: '主导航', exact: true }).locator('[aria-current="page"]')).toHaveText('快速练习')
    await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '面试历史', exact: true }).click()
    await expect(page).toHaveURL('/history')
    const row = page.locator('.history-row').filter({ has: page.locator(`a.history-entry[href="${path}"]`) })
    await expect(row).toContainText('快速练习')
    await expect(row).toContainText(`已回答 ${scenario.answerCount} 次`)
    await expect(row.getByRole('link', { name: '查看复盘', exact: true })).toHaveAttribute('href', path)
    const replayPath = `/history/interviews/${encodeURIComponent(initial.id)}/replay`
    await expect(row.getByRole('link', { name: '回放', exact: true })).toHaveAttribute('href', replayPath)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('quick-unified-history.png'), fullPage: true })
    const writesBeforeReplay = requests.filter(request => request.method !== 'GET').length
    await row.getByRole('link', { name: '回放', exact: true }).click()
    await expect(page).toHaveURL(replayPath)
    await expect(page.locator('.replay-summary')).toContainText('快速练习')
    await expect(page.locator('.replay-summary')).toContainText(`已回答 ${scenario.answerCount} 次`)
    await expect(page.locator('.message.user')).toHaveCount(scenario.answerCount)
    expect(await page.locator('.message.user .message-content').allTextContents()).toEqual(answers)
    await expect(page.locator('textarea, form')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '查看复盘', exact: true })).toHaveAttribute('href', path)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: testInfo.outputPath('quick-replay.png'), fullPage: true })
    await page.getByRole('link', { name: '查看复盘', exact: true }).click()
    await expect(page).toHaveURL(path)
    await expect(page.locator('[data-overview]')).toBeVisible()
    expect(requests.filter(request => request.method !== 'GET')).toHaveLength(writesBeforeReplay)
    expect(errors).toEqual([])
  })
}
