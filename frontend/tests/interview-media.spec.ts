import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'

for (const sessionType of ['QUICK', 'COMPREHENSIVE']) {
  for (const width of [375, 768, 1440]) {
    test(`${sessionType} ${width}px 作答前显示告知，听写只写入草稿且无摄像头入口`, async ({ page, request }, testInfo) => {
      await page.setViewportSize({ width, height: 1000 })
      await page.addInitScript(() => {
        // 明确隔离真实设备：识别只返回合成文字，任何设备采集均使测试失败。
        Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: () => { throw new Error('禁止真实设备采集') } })
        class Recognition {
          onstart?: () => void
          onend?: () => void
          onresult?: (event: unknown) => void
          start() {
            this.onstart?.()
            this.onresult?.({ resultIndex: 0, results: [{ isFinal: true, length: 1, 0: { transcript: '合成听写草稿', confidence: 1 } }] })
            this.onend?.()
          }
          stop() {}
          abort() {}
        }
        Object.defineProperty(window, 'SpeechRecognition', { value: Recognition })
      })
      const response = await request.post('/api/interviews', {
        data: { requestId: randomUUID(), sessionType, role: '合成Java后端岗位', level: 'JUNIOR', plannedQuestionCount: sessionType === 'QUICK' ? 3 : 8 },
      })
      expect(response.ok()).toBe(true)
      const { id } = await response.json()
      const read = async () => (await request.get(`/api/interviews/${id}`)).json()
      await expect.poll(async () => (await read()).status).toBe(sessionType === 'QUICK' ? 'IN_PROGRESS' : 'READY')
      await page.goto(`/interviews/${id}`)
      if (sessionType !== 'QUICK') await page.locator('[data-action="confirm"]').click()
      await page.locator('.modern-composer').scrollIntoViewIfNeeded()
      const notice = page.getByText('浏览器可能在线处理语音；本工具不保存音频', { exact: true })
      await expect(notice).toBeVisible()
      await expect(notice).toBeInViewport()
      await expect(page.locator('video, [aria-label*="摄像头"]')).toHaveCount(0)
      await expect(page.locator('.candidate-tile .avatar-container')).toBeAttached()
      const writes: string[] = []
      page.on('request', r => { if (r.method() === 'POST') writes.push(new URL(r.url()).pathname) })
      await page.getByRole('button', { name: '语音输入', exact: true }).click()
      await expect(page.locator('#interview-answer')).toHaveValue('合成听写草稿')
      await expect(page.locator('[data-action="answer"]')).toBeEnabled()
      expect(writes).toEqual([])
      expect((await read()).messages.filter((m: { role: string }) => m.role === 'USER')).toEqual([])
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('media-notice.png'), fullPage: true })
    })
  }
}
