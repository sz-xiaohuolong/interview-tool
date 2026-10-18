import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'

for (const width of [1440, 390]) {
  test(`${width}px 阶段跳过保留原答与复盘，页脚显示实际Token`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const response = await request.post('http://127.0.0.1:18121/api/v3/sessions', {
      data: { requestId: randomUUID(), role: '合成Java后端岗位', level: 'JUNIOR', plannedQuestionCount: 8 },
    })
    expect(response.ok()).toBeTruthy()
    const { id } = await response.json()
    const read = async () => (await request.get(`http://127.0.0.1:18121/api/v3/sessions/${id}`)).json()
    await expect.poll(async () => (await read()).status).toBe('READY')
    await page.goto(`/interview-v3/${id}`)
    await page.locator('[data-action="confirm"]').click()
    await expect(page.locator('[data-action="skip-stage"]')).toBeVisible()
    const calls = (await read()).usage.calls
    const writes: string[] = []
    page.on('request', request => { if (request.method() === 'POST') writes.push(new URL(request.url()).pathname) })
    await page.locator('#v3-answer').fill('合成未提交草稿')
    page.once('dialog', dialog => dialog.dismiss())
    await page.locator('[data-action="skip-stage"]').click()
    await expect(page.locator('#v3-answer')).toHaveValue('合成未提交草稿')
    expect(writes).toHaveLength(0)
    page.once('dialog', dialog => dialog.accept())
    await page.locator('[data-action="skip-stage"]').click()
    await expect.poll(async () => (await read()).stage).toBe('PROJECT')
    await expect(page.locator('[aria-label="面试四阶段"] li').first()).toContainText('已跳过')
    await expect(page.locator('#v3-answer')).toHaveValue('')
    expect((await read()).usage.calls).toBe(calls)

    const answer = '合成回答：我负责订单查询接口，使用请求编号排查数据库超时，返回明确错误。'
    await page.locator('#v3-answer').fill(answer)
    await page.locator('[data-action="answer"]').click()
    await expect.poll(async () => (await read()).status).toBe('IN_PROGRESS')
    for (const target of ['TECHNICAL', 'QUESTIONS']) {
      page.once('dialog', dialog => dialog.accept())
      await page.locator('[data-action="skip-stage"]').click()
      await expect.poll(async () => (await read()).stage).toBe(target)
    }
    await expect(page.locator('[data-action="skip-stage"]')).toHaveText('跳过反问，结束面试')
    const beforeFinalSkip = (await read()).usage.calls
    page.once('dialog', dialog => dialog.accept())
    await page.locator('[data-action="skip-stage"]').click()
    await expect.poll(async () => (await read()).status).toBe('COMPLETED')
    const completed = await read()
    expect(completed.usage.calls).toBe(beforeFinalSkip)
    expect(completed.messages.filter((message: any) => message.role === 'USER').map((message: any) => message.content)).toEqual([answer])
    expect(completed.stages.map((stage: any) => stage.status)).toEqual(['SKIPPED', 'SKIPPED', 'SKIPPED', 'SKIPPED'])
    await expect(page.locator('[data-action="finish"]')).toHaveText('生成查证复盘')
    await page.locator('[data-action="finish"]').click()
    await expect.poll(async () => (await read()).status).toBe('REPORT_READY')
    await expect(page.locator('[aria-label="本场四阶段完成情况"]')).toContainText('已跳过')
    await expect(page.locator('[data-overview]')).toContainText('跳过反问后结束')
    await expect(page.locator('[data-token-usage]')).toContainText('合计 0')
    await expect(page.locator('footer')).not.toContainText('实际费用未知')
    expect(writes.every(path => path.startsWith(`/api/v3/sessions/${id}/`))).toBeTruthy()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
    await page.screenshot({ path: testInfo.outputPath('stage-controls.png'), fullPage: true })
  })
}
