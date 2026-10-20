import { expect, test } from '@playwright/test'

// 浏览器用合成 HTTP 快照检查展示边界；真实后端接线由 SourceInterviewIntegrationTest 覆盖。
const commitSha = 'a'.repeat(40)
const source = { repository: 'acme/orders', commitSha, status: 'READY', evidenceCount: 1 }
const evidence = { id: 'code-1', path: 'src/Orders.java', startLine: 2, endLine: 3, snippet: 'void save() {\n throw new IllegalStateException();\n}', verified: true, goal: '内部考查目标，不对外展示' }
for (const width of [1440, 390]) {
  test(`源码准备失败显式继续与复盘固定引用：${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 950 })
    let continued = false
    const writes: string[] = []
    await page.route('**/api/interviews/source-browser{,/**}', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      if (path === '/api/interviews/source-browser/continue-without-source' && request.method() === 'POST') { continued = true; writes.push(path) }
      else if (path !== '/api/interviews/source-browser' || request.method() !== 'GET') throw new Error(`未知合成请求：${request.method()} ${path}`)
      await route.fulfill({ json: {
        id: 'source-browser', status: continued ? 'READY' : 'FAILED', phase: '合成源码状态', revision: continued ? 2 : 1,
        role: 'Java 后端', level: 'JUNIOR', mock: true, createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z',
        sourceCode: { ...source, status: continued ? 'SKIPPED' : 'FAILED', error: continued ? '' : '合成 GitHub 限流' },
        summary: {}, messages: [], sources: [], warnings: [], allowedActions: continued ? ['confirm'] : ['retry', 'continue-without-source'],
      } })
    })
    await page.goto('/interviews/source-browser')
    await expect(page.getByText('合成 GitHub 限流', { exact: true })).toBeVisible()
    expect(writes).toEqual([])
    await page.getByRole('button', { name: '仅使用项目资料继续', exact: true }).click()
    await expect(page.getByLabel('本场源码依据')).toContainText('仅使用项目资料')
    expect(writes).toEqual(['/api/interviews/source-browser/continue-without-source'])
    expect(await page.locator('a[href*="github.com"]').count()).toBe(0)

    await page.route('**/api/sessions/source-review{,/**}', async route => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() !== 'GET') throw new Error('复盘不得写入')
      if (path === '/api/sessions/source-review') await route.fulfill({ json: {
        id: 'source-review', status: 'COMPLETED', mode: 'PROJECT', role: 'Java 后端', level: 'JUNIOR', durationMinutes: 5, style: 'NORMAL',
        createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z', endedAt: '2026-10-20T00:01:00Z', reportStatus: 'READY', messages: [], sourceCode: source,
      } })
      else if (path === '/api/sessions/source-review/report') await route.fulfill({ json: {
        sessionId: 'source-review', summary: '合成源码复盘', dimensions: [], questions: [], weaknesses: [], suggestions: [],
        sourceCode: { ...source, evidence: [evidence] }, sourceQuestions: [{ question: '校验失败会如何终止这条请求？', codeEvidenceIds: ['code-1'] }],
      } })
      else throw new Error(`未知合成请求：${path}`)
    })
    await page.goto('/reports/source-review')
    const section = page.getByLabel('本场源码依据')
    await expect(section).toContainText('acme/orders')
    await expect(section.getByText('校验失败会如何终止这条请求？')).toBeVisible()
    await section.locator('summary').click()
    await expect(section).toContainText('IllegalStateException')
    await expect(section.getByRole('link')).toHaveAttribute('href', `https://github.com/acme/orders/blob/${commitSha}/src/Orders.java#L2-L3`)
    await expect(page.getByText(evidence.goal)).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/source-review-${width}.png`, fullPage: true })
  })
}
