import { expect, test } from '@playwright/test'

test('默认应用从综合面试进入综合准备，提交回答并刷新恢复', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '综合面试', exact: true }).click()
  await expect(page).toHaveURL('/interviews')
  await page.locator('#interview-role').selectOption('Java 后端工程师')
  await page.locator('#interview-level').selectOption('JUNIOR')
  const created = page.waitForResponse(response => response.url().endsWith('/api/interviews') && response.request().method() === 'POST')
  await page.locator('[data-action="prepare"]').click()
  expect((await created).ok()).toBe(true)
  await expect(page).toHaveURL(/\/interviews\/[^/]+$/)
  const path = new URL(page.url()).pathname
  await page.locator('[data-action="confirm"]').click()
  const answer = '合成回答：我负责订单接口，通过唯一请求编号处理重复提交，并记录失败原因。'
  await page.locator('#interview-answer').fill(answer)
  await page.locator('[data-action="answer"]').click()
  await expect(page.locator('#interview-answer')).toBeEnabled()
  await page.reload()
  await expect(page).toHaveURL(path)
  await expect(page.locator('#interview-answer')).toBeEnabled()
  await expect(page.locator('.interview-dialogue')).toContainText(answer)
  await page.locator('[data-action="finish"]').click()
  await expect(page.locator('[data-overview]')).toBeVisible()
  await page.goto(path.replace('/interviews/', '/interview-v3/'))
  await expect(page.locator('[data-overview]')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主导航', exact: true }).locator('[aria-current="page"]')).toHaveText('综合面试')
})

test('综合接口不可用时保留综合入口并显示错误，不请求旧面试接口', async ({ page }) => {
  const paths: string[] = []
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) paths.push(new URL(request.url()).pathname) })
  await page.route('**/api/interviews{,/**}', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '合成综合服务不可用' }) }))
  await page.goto('/interviews')
  await expect(page).toHaveURL('/interviews')
  await expect(page.getByRole('alert').filter({ hasText: '合成综合服务不可用' })).toBeVisible()
  expect(paths).toContain('/api/interviews/status')
  expect(paths).toContain('/api/interviews')
  expect(paths).not.toContain('/api/sessions')
})

for (const status of [200, 503]) {
  test(`专项训练关闭或状态探测失败时仍可进入快速练习和综合面试：${status}`, async ({ page }) => {
    await page.route('**/api/training/status', route => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ enabled: false }) }))
    await page.goto('/configure/quick')
    await expect(page).toHaveURL('/configure/quick')
    await expect(page.locator('#quick-role')).toBeVisible()
    await expect(page.locator('#quick-question-count')).toBeVisible()
    await expect(page.getByRole('button', { name: '开始快速练习', exact: true })).toBeVisible()
    await expect(page.getByRole('navigation', { name: '主导航', exact: true })).not.toContainText('专项训练')
    await expect(page.locator('#app > .app-shell')).toHaveCount(1)
    await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '综合面试', exact: true }).click()
    await expect(page).toHaveURL('/interviews')
    await expect(page.locator('[data-action="prepare"]')).toBeVisible()
  })
}
