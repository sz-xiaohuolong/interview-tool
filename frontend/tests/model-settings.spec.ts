import { expect, test } from '@playwright/test'
import { createServer, type Server } from 'node:http'

let server: Server
let baseUrl = ''
let calls = 0
test.beforeAll(async () => {
  server = createServer((req, res) => {
    calls++
    res.setHeader('Content-Type', 'application/json')
    if (req.url === '/v1/models') return res.end(JSON.stringify({ data: [{ id: 'synthetic-model' }] }))
    let data = ''
    req.on('data', chunk => { data += chunk })
    req.on('end', () => {
      const body = JSON.parse(data)
      expect(body.response_format.type).toBe('json_object')
      res.end(JSON.stringify({ model: 'synthetic-model', choices: [{ message: { role: 'assistant', content: '{"ok":true}' }, finish_reason: 'stop' }], usage: { prompt_tokens: 11, completion_tokens: 3, total_tokens: 14 } }))
    })
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}/v1`
})
test.afterAll(async () => { await new Promise<void>(resolve => server.close(() => resolve())) })

test('全站配置保存、草稿保留、主动探测、密钥不回显及恢复启动配置', async ({ page, request }, testInfo) => {
  test.skip(testInfo.project.name !== 'model-settings', '此组使用独立的 V3 日常入口配置。')
  await page.goto('/interview-v3')
  const draftText = page.locator('#v3-jd-text')
  await draftText.fill('岗位描述草稿')
  await page.getByLabel('打开模型配置').click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('模型配置', { exact: true })).toBeVisible()
  await dialog.locator('#model-preset').selectOption('CUSTOM')
  await dialog.locator('#model-url').fill(baseUrl)
  await dialog.locator('#model-key').fill('synthetic-browser-key')
  await dialog.locator('#model-id').fill('synthetic-model')
  const before = calls
  await dialog.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(dialog.getByText('已保存，后续模型请求使用此配置。')).toBeVisible()
  expect(calls).toBe(before)
  await expect(page.getByLabel('打开模型配置')).toContainText('synthetic-model')
  const health = await (await request.get('/api/v3/status')).json()
  expect(health).toMatchObject({ provider: 'openai', model: 'synthetic-model', configured: true })
  const visible = await (await request.get('/api/model-config')).text()
  expect(visible).not.toContain('synthetic-browser-key')
  await expect(dialog.locator('#model-key')).toHaveValue('')
  await dialog.getByRole('button', { name: '获取模型列表' }).click()
  await expect(dialog.getByText('已获取 1 个模型，也可手动填写。')).toBeVisible()
  await dialog.getByRole('button', { name: '测试连接', exact: true }).click()
  await expect(dialog.locator('.test-result')).toContainText('实际 14 Token')
  expect(calls).toBe(before + 2)
  await page.screenshot({ path: testInfo.outputPath('model-settings-desktop.png'), animations: 'disabled' })
  await dialog.getByLabel('关闭弹窗').click()
  // 页面没有重建，表单中输入的内容在切换模型后仍保留。
  await expect(draftText).toHaveValue('岗位描述草稿')
  await page.getByLabel('打开模型配置').click()
  await expect(dialog.locator('#model-id')).toHaveValue('synthetic-model')
  await expect(dialog.locator('#model-key')).toHaveValue('')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('model-settings-mobile.png'), animations: 'disabled' })
  const overflow = await dialog.evaluate(el => el.scrollWidth > el.clientWidth + 1)
  expect(overflow).toBe(false)
  page.once('dialog', d => d.accept())
  await dialog.getByRole('button', { name: '恢复启动配置' }).click()
  await expect(dialog.getByText('已恢复启动配置。')).toBeVisible()
  await expect(page.getByLabel('打开模型配置')).toContainText('Mock')
  await dialog.getByLabel('关闭弹窗').click()
  await expect(draftText).toHaveValue('岗位描述草稿')
  expect(calls).toBe(before + 2)
})
