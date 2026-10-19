import { test, expect } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`已保存故事删除：${width}px 下归档故事可取消、删除并保持刷新结果`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    async function create(templateKind: string) {
      const response = await page.request.post('/api/training/stories', { data: { templateKind, requestId: `create-${templateKind}-${width}` } })
      expect(response.ok()).toBe(true)
      return response.json()
    }
    const story = await create('INTRO')
    const kept = await create('STAR')
    const archived = await page.request.post(`/api/training/stories/${story.id}/archive`, { data: { expectedVersion: story.revision, requestId: `archive-${width}` } })
    expect(archived.ok()).toBe(true)
    await page.goto('/stories')
    const row = page.locator('article.training-list-row').filter({ has: page.locator(`a[href="/stories/${story.id}"]`) })
    await expect(row).toContainText('已归档')
    await expect(row.getByRole('button', { name: '删除', exact: true })).toBeVisible()
    await page.locator('#story-action').fill('合成测试：我尚未保存的新故事行动。')
    await row.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('saved-stories-delete.png'), fullPage: true })
    page.once('dialog', dialog => dialog.dismiss())
    await row.getByRole('button', { name: '删除', exact: true }).click()
    await expect(row).toBeVisible()
    await expect(page).toHaveURL(/\/stories$/)
    page.once('dialog', dialog => dialog.accept())
    await row.getByRole('button', { name: '删除', exact: true }).focus()
    await row.getByRole('button', { name: '删除', exact: true }).press('Enter')
    await expect(row).toHaveCount(0)
    await expect(page.locator('#story-action')).toHaveValue('合成测试：我尚未保存的新故事行动。')
    await expect(page.locator(`a[href="/stories/${kept.id}"]`)).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: '已保存故事', exact: true })).toBeVisible()
    await expect(row).toHaveCount(0)
    await expect(page.locator(`a[href="/stories/${kept.id}"]`)).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBe(width)
    expect(errors).toEqual([])
  })
}
