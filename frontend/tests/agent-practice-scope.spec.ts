import { expect, test } from '@playwright/test'
import { agentTopicLabels } from '../src/api/interviews'

// 使用隔离环境的显式 Mock，只验证主题配置、页面展示与刷新恢复。
for (const width of [1440, 390]) {
  test(`Agent 全选六类、五题抽查与参考资料分开展示：${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/configure/quick')
    await page.locator('#quick-role').selectOption('大模型应用 / Agent 开发工程师')
    await page.locator('.quick-level > summary').click()
    for (const id of Object.keys(agentTopicLabels)) await page.locator(`#quick-topic-${id}`).check()
    await expect(page.locator('[data-agent-topics]')).toContainText('全选也不保证一场覆盖全部主题')
    const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/interviews' && response.request().method() === 'POST')
    await page.getByRole('button', { name: '开始快速练习', exact: true }).click()
    const response = await created
    expect(response.ok()).toBe(true)
    expect(response.request().postDataJSON()).toMatchObject({ sessionType: 'QUICK', plannedQuestionCount: 5, agentTopics: Object.keys(agentTopicLabels) })
    await expect(page.locator('#interview-answer')).toBeEnabled()

    for (const reload of [false, true]) {
      if (reload) await page.reload()
      const disclosure = page.locator('.interview-sources-disclosure')
      await disclosure.locator(':scope > summary').click()
      const scope = page.locator('[data-agent-practice-scope]')
      await expect(scope.locator('[data-agent-topic]')).toHaveCount(6)
      await expect(scope).toContainText('框架实践 / 模型接入')
      await expect(scope).toContainText('计划 5 道主问题')
      await expect(scope).toContainText('不保证每类都考到')
      await expect(scope).toContainText('具体考点覆盖在复盘中查看')
      await expect(page.locator('.interview-sources-count')).toContainText('份资料')
      await expect(page.locator('[data-followup-diagnostics], [data-topic-coverage]')).toHaveCount(0)
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1)
    }
    await page.locator('.interview-sources-disclosure').screenshot({ path: testInfo.outputPath(`agent-scope-${width}.png`) })
  })
}
