import { test, expect } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`面试历史删除：${width}px 下取消、确认、筛选与刷新`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    const role = `合成删除测试 ${width}`
    const completedRole = `合成已完成删除 ${width}`
    const keptRole = `合成保留记录 ${width}`
    async function create(name: string) {
      const response = await page.request.post('/api/sessions', { data: { mode: 'QUICK', durationMinutes: 5, role: name } })
      expect(response.ok()).toBe(true)
      return (await response.json()).id as string
    }
    const activeId = await create(role)
    const completedId = await create(completedRole)
    expect((await page.request.post(`/api/sessions/${completedId}/finish`, { data: {} })).ok()).toBe(true)
    await create(keptRole)
    await page.goto('/history')
    const activeRow = page.locator('.history-row').filter({ hasText: role })
    const completedRow = page.locator('.history-row').filter({ hasText: completedRole })
    await expect(activeRow.getByRole('link', { name: '继续面试' })).toHaveAttribute('href', `/sessions/${activeId}`)
    await expect(completedRow.getByRole('link', { name: '查看复盘' })).toHaveAttribute('href', `/reports/${completedId}`)
    await page.screenshot({ path: testInfo.outputPath('history-before-delete.png'), fullPage: true })
    page.once('dialog', dialog => dialog.dismiss())
    await activeRow.getByRole('button', { name: `删除${role}的面试记录` }).click()
    await expect(activeRow).toBeVisible()
    await expect(page).toHaveURL(/\/history$/)
    page.once('dialog', dialog => dialog.accept())
    const deleteButton = activeRow.getByRole('button', { name: `删除${role}的面试记录` })
    await deleteButton.focus()
    await deleteButton.press('Enter')
    await expect(activeRow).toHaveCount(0)
    await expect(page).toHaveURL(/\/history$/)
    await page.reload()
    await expect(page.getByText(role, { exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: '已完成', exact: true }).click()
    await expect(completedRow).toBeVisible()
    page.once('dialog', dialog => dialog.accept())
    await completedRow.getByRole('button', { name: `删除${completedRole}的面试记录` }).click()
    await expect(completedRow).toHaveCount(0)
    await page.getByRole('button', { name: '全部练习', exact: true }).click()
    await expect(page.getByText(keptRole, { exact: true })).toBeVisible()
    await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBe(width)
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(errors).toEqual([])
  })
}
