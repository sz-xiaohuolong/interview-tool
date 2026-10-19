import { expect, test } from '@playwright/test'
import type { InterviewSession } from '../src/models/interview'

// 显式合成报告仅用于展示验收；所有接口都由本用例按路径返回，不读取个人场次。
const time = '2026-10-18T08:00:00Z'
const session: InterviewSession = {
  id: 'report-layout', status: 'REPORT_READY', phase: 'REPORT_READY', revision: 8,
  role: '合成 Java 后端岗位', level: 'JUNIOR', durationMinutes: 10, plannedQuestionCount: 8,
  experienceVersion: 1, reviewVersion: 2, interviewMode: 'TRAINING', mock: true,
  createdAt: time, updatedAt: time, warnings: [], allowedActions: [],
  messages: [
    { id: 'm1', role: 'ASSISTANT', questionId: 'q1', rootQuestionId: 'q1', questionKind: 'MAIN', content: '结合订单查询项目，说明超时与重试的边界。', createdAt: time },
    { id: 'm2', role: 'USER', questionId: 'q1', content: '数据库超时后返回错误。\n我负责接口与异常日志。', createdAt: time },
    { id: 'm3', role: 'ASSISTANT', questionId: 'q2', rootQuestionId: 'q1', questionKind: 'FOLLOW_UP', content: '如何避免重复执行？', createdAt: time },
    { id: 'm4', role: 'USER', questionId: 'q2', content: '使用唯一键避免重复写入，并记录冲突处理结果。', createdAt: time },
  ],
  sources: [{ id: 'official', title: '合成技术来源', kind: 'OFFICIAL', url: 'https://example.com/reference', version: '合成版本', fetchedAt: time, sha256: 'a'.repeat(64), excerpt: '合成片段：需要区分重试条件与幂等约束。', topics: [] }],
  report: {
    version: 2,
    summary: '本场能围绕本人承担的接口开发说明具体行动，已交代唯一键防重复措施。\n\n超时后的重试条件与失败处理仍需结合实际调用链展开，当前回答不足以验证这些边界。\n\n后续可补充失败场景、处理步骤与验证方式；本结论仅基于本场已提交的文字。',
    overview: { answeredCount: 2, mainQuestionCount: 1, followUpCount: 1, endReason: 'USER_FINISHED' },
    stages: [
      { id: 'INTRO', label: '开场介绍', status: 'COMPLETED', answeredCount: 0 },
      { id: 'PROJECT', label: '项目深挖', status: 'COMPLETED', answeredCount: 2 },
      { id: 'TECHNICAL', label: '技术基础', status: 'NOT_STARTED', answeredCount: 0 },
      { id: 'QUESTIONS', label: '候选人反问', status: 'NOT_STARTED', answeredCount: 0 },
    ],
    findings: [
      { questionId: 'q1', headline: '超时后的处理边界待补充', answerStatus: 'PARTIAL', answerQuote: '数据库超时后返回错误', observation: '说明了返回错误，尚未交代重试条件与失败后的处理。', assessment: '仅凭这段回答，还不能判断是否考虑了重复执行。', status: 'INSUFFICIENT', sourceIds: ['official'], sourceQuote: '需要区分重试条件与幂等约束', suggestion: '用一次真实失败场景串起触发、重试与最终结果。', priority: 1 },
      { questionId: 'q2', headline: '防重复措施具体', answerStatus: 'CLEAR', answerQuote: '使用唯一键避免重复写入', observation: '能说明幂等约束，并交代冲突结果的记录。', assessment: '本场给出了可回查的具体措施，技术适用性仍须结合实际场景。', status: 'INSUFFICIENT', sourceIds: [], suggestion: '继续交代唯一键范围与冲突处理。' },
    ],
    matrix: { version: 1, project: { id: 'project', name: '合成订单查询项目' }, items: [
      { checkpointId: 'BUSINESS', title: '业务背景', status: 'CLEAR', reason: '已说明订单查询的业务场景。', evidence: [] },
      { checkpointId: 'RESPONSIBILITY', title: '个人职责', status: 'CLEAR', reason: '已说明本人负责接口与异常日志。', evidence: [{ questionMessageId: 'm1', answerMessageId: 'm2', questionQuote: '说明超时与重试的边界', answerQuote: '我负责接口与异常日志' }] },
      { checkpointId: 'ARCHITECTURE', title: '系统架构', status: 'PARTIAL', reason: '调用链仍需补充。', evidence: [] },
      { checkpointId: 'TECH_CHOICE', title: '技术选型', status: 'UNCOVERED', reason: '本场未覆盖。', evidence: [] },
      { checkpointId: 'FAILURE', title: '异常处理', status: 'PARTIAL', reason: '尚缺少失败边界。', evidence: [] },
      { checkpointId: 'PERFORMANCE', title: '性能与结果', status: 'UNCOVERED', reason: '本场未覆盖。', evidence: [] },
      { checkpointId: 'TRADEOFF', title: '方案取舍', status: 'UNCOVERED', reason: '本场未覆盖。', evidence: [] },
    ] },
  },
}

for (const width of [1440, 390, 320]) {
  test(`${width}px 复盘紧凑布局、原答定位与默认折叠保持只读`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const writes: string[] = [], unknown: string[] = [], errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      if (request.method() !== 'GET') writes.push(`${request.method()} ${path}`)
      const responses: Record<string, unknown> = {
        '/api/interviews/status': { enabled: true, provider: 'mock', configured: true },
        '/api/training/status': { enabled: true },
        '/api/health': { status: 'ok', provider: 'mock', configured: true, model: 'mock-functional-test' },
        '/api/materials': [],
        '/api/interviews/report-layout': session,
        '/api/project-evidence/V3/report-layout/gaps': { items: [] },
        '/api/review-gaps/V3/report-layout': { items: [{ questionId: 'q1', caseId: 'saved-case', status: 'READY', latestAttempt: { id: 'saved-attempt', status: 'READY' } }] },
      }
      if (request.method() === 'GET' && path in responses) await route.fulfill({ json: responses[path] })
      else { unknown.push(`${request.method()} ${path}`); await route.fulfill({ status: 404, json: { message: '合成验收未定义此接口' } }) }
    })

    await page.goto('/interviews/report-layout')
    await expect(page.getByRole('heading', { name: '详细评估', exact: true })).toBeVisible()
    await expect(page.locator('.review-assessment-body')).toHaveText(session.report!.summary)
    const strengths = page.locator('[data-strengths]'), improvements = page.locator('[data-improvements]')
    const left = (await strengths.boundingBox())!, right = (await improvements.boundingBox())!
    if (width === 1440) {
      expect(Math.abs(left.y - right.y)).toBeLessThan(1)
      expect(left.x + left.width).toBeLessThan(right.x)
    } else expect(left.y + left.height).toBeLessThan(right.y)
    await expect(improvements.getByRole('button', { name: /补练/ })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '项目证据覆盖与缺口', exact: true })).toBeVisible()
    await expect(page.locator('[data-checkpoint]')).toHaveCount(7)

    const training = page.locator('[data-review-training]'), summary = training.locator('summary')
    const resume = training.getByRole('link', { name: '继续定向补练', exact: true })
    const transcript = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: /^查看完整原始对话$/ }) })
    await expect(training).not.toHaveAttribute('open')
    await expect(resume).toBeHidden()
    await expect(transcript).not.toHaveAttribute('open')
    await page.screenshot({ path: testInfo.outputPath('report-collapsed.png'), fullPage: true })

    await improvements.getByRole('button', { name: '定位原回答', exact: true }).click()
    await expect(page.locator('[data-question-group]')).toHaveAttribute('open')
    await expect(page.locator('[data-answer="q1"]')).toBeFocused()
    await expect(page.locator('[data-answer="q1"]')).toContainText('原答摘录：')
    await expect(page.locator('[data-answer="q1"]')).toContainText('数据库超时后返回错误')
    await page.getByRole('link', { name: '合成技术来源', exact: true }).click()
    await expect(page.locator('#interview-source-official')).toHaveAttribute('open')
    await expect(page.locator('#interview-source-official summary')).toBeFocused()

    await summary.focus(); await summary.press('Enter')
    await expect(resume).toBeVisible()
    await expect(resume).toHaveAttribute('href', '/practice/saved-case')
    await expect(training.getByRole('link', { name: '查看补练结果', exact: true })).toHaveAttribute('href', '/practice/saved-case?attemptId=saved-attempt')
    await summary.press('Space')
    await expect(resume).toBeHidden()
    await transcript.locator('summary').click()
    await expect(transcript).toContainText('我负责接口与异常日志。')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('report-expanded.png'), fullPage: true })
    await page.reload()
    await expect(training).not.toHaveAttribute('open')
    await expect(resume).toBeHidden()
    expect(writes).toEqual([])
    expect(unknown).toEqual([])
    expect(errors).toEqual([])
  })
}
