import { expect, test } from '@playwright/test'

// 隔离合成库使用显式 Mock，验证实际API、身份隐藏与复盘展示，不评定模型质量。
for (const width of [1440, 390]) {
  test(`独立原题与获取详情刷新后可回查：${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/configure/quick')
    await page.locator('#quick-role').selectOption('大模型应用 / Agent 开发工程师')
    await page.locator('#quick-question-count').selectOption('3')
    const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
    const response = await created
    expect(response.ok()).toBe(true)
    const initial = await response.json()
    const api = `/api/interviews/${encodeURIComponent(initial.id)}`
    await expect(page.locator('#interview-answer')).toBeEnabled()
    const before = await (await page.request.get(api)).json()
    expect(before.mock).toBe(true)
    expect(JSON.stringify(before)).not.toContain('seedQuestionId')
    await page.locator('.interview-sources-disclosure > summary').click()
    await page.locator('[data-retrieval-details] > summary').click()
    await expect(page.locator('[data-retrieval-details]')).toContainText('NETWORK_DISABLED')
    await expect(page.locator('[data-retrieval-details]')).toContainText('本次未获取到参考文档')
    await page.locator('[data-action="finish"]').click()
    await expect(page.locator('[data-topic-coverage]')).toBeVisible()
    const saved = await (await page.request.get(api)).json()
    const item = saved.report.topicCoverage.items[0]
    expect(item.seedQuestionId).toMatch(/^jb-/)
    expect(item.questionId).not.toBe(item.seedQuestionId)
    await page.reload()
    const seed = page.locator(`[data-seed-question="${item.seedQuestionId}"]`)
    await expect(seed).toContainText(item.seedQuestionTitle)
    await expect(seed.locator('a')).toHaveAttribute('href', item.seedQuestionUrl)
    await expect(seed.locator('a')).toHaveAttribute('rel', 'noopener noreferrer')
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1)
  })
}
