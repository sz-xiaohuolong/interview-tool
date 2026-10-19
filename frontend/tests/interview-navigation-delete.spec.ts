import { test, expect } from '@playwright/test'
import type { InterviewSession } from '../src/models/interview'

for (const width of [1440, 390]) {
  test(`综合面试导航与删除：${width}px 正确高亮、确认、刷新和回放失效`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    const role = `合成新版删除 ${width}`
    const created = await page.request.post('/api/interviews', { data: { requestId: crypto.randomUUID(), role, level: 'JUNIOR' } })
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
    expect((await page.request.post(`${url}/finish`, { data: { requestId: crypto.randomUUID(), expectedRevision: session.revision } })).ok()).toBe(true)
    await waitFor('REPORT_READY')
    const legacyRole = `合成旧版保留 ${width}`
    expect((await page.request.post('/api/sessions', { data: { mode: 'QUICK', durationMinutes: 5, role: legacyRole } })).ok()).toBe(true)
    await page.goto('/')
    await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '综合面试', exact: true }).click()
    await expect(page).toHaveURL('/interviews')
    const selected = page.locator('nav[aria-label="主导航"] a[aria-current="page"]')
    await expect(selected).toHaveCount(1)
    await expect(selected).toHaveText('综合面试')
    await expect(selected).toHaveClass(/router-link-active/)
    await page.screenshot({ path: testInfo.outputPath('start-interview-active.png'), fullPage: true })
    await page.goto(`/interviews/${session.id}`)
    await expect(selected).toHaveText('综合面试')
    await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '面试历史', exact: true }).click()
    await expect(selected).toHaveText('面试历史')
    const row = page.locator('.history-row').filter({ hasText: role })
    const remove = row.getByRole('button', { name: `删除${role}的面试记录` })
    await expect(row.getByRole('link', { name: '查看复盘', exact: true })).toBeVisible()
    await expect(remove).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('interview-delete-button.png'), fullPage: true })
    page.once('dialog', dialog => dialog.dismiss())
    await remove.click()
    await expect(row).toBeVisible()
    const deleted = page.waitForResponse(response => response.url().endsWith(url) && response.request().method() === 'DELETE')
    page.once('dialog', dialog => dialog.accept())
    await remove.focus()
    await remove.press('Enter')
    expect((await deleted).status()).toBe(204)
    await expect(row).toHaveCount(0)
    await expect(page).toHaveURL('/history')
    await page.reload()
    await expect(row).toHaveCount(0)
    await expect(page.getByText(legacyRole, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '已完成', exact: true }).click()
    await expect(row).toHaveCount(0)
    expect((await page.request.get(url)).status()).toBe(404)
    await page.goto(`/history/interviews/${session.id}/replay`)
    await expect(page.getByRole('alert')).toContainText('面试不存在或已删除')
    await page.getByRole('link', { name: '返回面试历史' }).click()
    await expect(page.getByText(legacyRole, { exact: true })).toBeVisible()
    await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBe(width)
    expect(errors).toEqual([])
  })
}
