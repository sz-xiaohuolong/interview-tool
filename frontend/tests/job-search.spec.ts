import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

// 招聘内容由专用合成 CDP 浏览器提供；应用 HTTP、Vue 与 SQLite 均为真实实现。
const api = '/api/job-search'
const screens = resolve('../.evidence/job-search/screens')
async function idle(request: APIRequestContext, id: string) {
  let task: any
  await expect.poll(async () => { const response = await request.get(`${api}/tasks/${id}`); expect(response.ok()).toBe(true); task = await response.json(); return task.status }, { timeout: 35_000 }).not.toBe('RUNNING')
  return task
}
async function start(page: Page, request: APIRequestContext, keyword = '合成技术岗', sources = ['BOSS', 'LIEPIN', 'ZHAOPIN']) {
  await page.locator('#job-keyword').fill(keyword)
  await page.locator('#job-city').fill('深圳')
  await page.locator('#job-city-option-shenzhen').click()
  for (const source of ['BOSS', 'LIEPIN', 'ZHAOPIN']) await page.locator(`#job-search-platform-${source}`).setChecked(sources.includes(source))
  const response = page.waitForResponse(response => new URL(response.url()).pathname === `${api}/tasks` && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  const created = await (await response).json()
  const task = await idle(request, created.id)
  if (task.jobs.length) await expect(page.locator('[data-job-select]')).toHaveCount(Math.min(20, task.jobs.length))
  return task
}
async function select(page: Page, key: string) {
  await page.locator(`[data-job-select="${key}"]`).click()
  await expect(page.locator('[data-job-detail]')).toBeVisible()
}
async function noOverflow(page: Page, width: number) {
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(width)
}
async function capture(page: Page, name: string) {
  await mkdir(screens, { recursive: true })
  await page.screenshot({ path: resolve(screens, `${name}.png`), fullPage: !await page.getByRole('dialog').count(), animations: 'disabled' })
}
test.beforeEach(async ({ request }) => {
  const response = await request.get(`${api}/favorites`)
  expect(response.ok()).toBe(true)
  for (const favorite of await response.json()) expect((await request.delete(`${api}/favorites/${favorite.id}`)).ok()).toBe(true)
  for (const material of await (await request.get('/api/materials')).json()) {
    if (material.name.startsWith('合成搜索验收')) expect((await request.delete(`/api/materials/${material.id}`)).ok()).toBe(true)
  }
})

test('所有城市发送全国条件并保留真实地点及可判定岗位类型', async ({ page, request }) => {
  await page.goto('/job-search')
  await page.locator('#job-keyword').fill('合成全国 Java')
  await page.locator('#job-city').fill('所有')
  await page.locator('#job-city-option-all').click()
  const createdResponse = page.waitForResponse(response => new URL(response.url()).pathname === `${api}/tasks` && response.request().method() === 'POST')
  await page.getByRole('button', { name: '开始搜索', exact: true }).click()
  const created = await (await createdResponse).json()
  try {
    let task = await idle(request, created.id)
    expect(task.cityId).toBe('all')
    expect(task.sources.map((source: any) => source.status)).toEqual(['READY', 'READY', 'READY'])
    expect(task.jobs.filter((job: any) => job.source === 'BOSS').map((job: any) => job.jobType)).toContain('DAILY_INTERNSHIP')
    expect(task.jobs.filter((job: any) => job.source === 'ZHAOPIN').every((job: any) => job.jobType === 'FULL_TIME')).toBe(true)
    const liepin = task.jobs.filter((job: any) => job.source === 'LIEPIN')
    expect(new Set(liepin.map((job: any) => job.city))).toEqual(new Set(['青岛', '武汉']))
    expect(liepin.every((job: any) => job.company === '合成猎聘科技' && job.jobType === 'UNKNOWN')).toBe(true)
    await request.post(`${api}/tasks/${task.id}/more`, { data: {} })
    task = await idle(request, task.id)
    expect(task.sources.map((source: any) => source.status)).toEqual(['READY', 'READY', 'READY'])
    expect(task.jobs.filter((job: any) => job.source === 'LIEPIN').length).toBe(40)
    expect(task.jobs.filter((job: any) => job.source === 'ZHAOPIN').every((job: any) => job.jobType === 'FULL_TIME')).toBe(true)
  } finally { await request.post(`${api}/tasks/${created.id}/stop`) }
})

test('三站分批、来源身份、原文筛选与真实详情不会调用面试接口', async ({ page, request }) => {
  const errors: string[] = [], writes: string[] = []
  // 本例验证持续双栏中的筛选流程；低高度降级由专门场景覆盖。
  await page.setViewportSize({ width: 1440, height: 1400 })
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname) })
  await page.goto('/')
  await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '岗位搜索', exact: true }).click()
  let task = await start(page, request)
  expect(task.sources.map((source: any) => source.status)).toEqual(['READY', 'READY', 'READY'])
  expect(task.jobs.length).toBe(55)
  expect(new Set(task.jobs.map((job: any) => job.key)).size).toBe(task.jobs.length)
  for (const job of task.jobs) { expect(job.key).toBe(`${job.source}:${job.sourceId}`); expect(job.url).not.toContain('?'); expect(job.title).toContain('合成'); expect(job.title).not.toContain('合成广告'); expect(job.title).not.toContain('合成隐藏') }
  const first = task.jobs.find((job: any) => job.source === 'BOSS')
  expect(first.salaryText).toBe('20-30K·13薪')
  await select(page, first.key)
  await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
  await expect(page.getByRole('link', { name: '查看原岗位（新标签打开）' })).toHaveAttribute('href', first.url)
  await page.locator('#job-source-filter').selectOption('BOSS')
  await expect(page.locator('[data-job-select]')).toHaveCount(15)
  await page.getByLabel('保留未知／面议', { exact: true }).uncheck()
  await expect(page.locator('[data-job-select]')).toHaveCount(14)
  await page.locator('#job-salary-filter summary').click()
  await page.getByLabel('最低月薪').fill('40'); await page.getByLabel('最高月薪').fill('50'); await page.getByLabel('最高月薪').press('Tab')
  await expect(page.getByText('当前筛选无匹配', { exact: true })).toBeVisible()
  await expect(page.locator('[data-job-detail]')).toHaveCount(0)
  await page.getByRole('region', { name: '筛选已获取结果', exact: true }).getByRole('button', { name: '清除筛选', exact: true }).click()
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await expect(page.locator('[data-job-detail]')).toHaveCount(0)
  await expect(page.getByText('第 1-20 条／共 55 条岗位', { exact: false })).toBeVisible()
  await page.locator('[data-pagination-page="2"]').click()
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await expect(page.getByText('第 21-40 条／共 55 条岗位', { exact: false })).toBeVisible()
  await page.locator('[data-pagination-page="1"]').click()
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  const oldKeys = new Set(task.jobs.map((job: any) => job.key))
  const nextBatch = page.waitForResponse(response => response.url().endsWith(`/tasks/${task.id}/more`) && response.request().method() === 'POST')
  await page.locator('[data-pagination-page="3"]').click()
  await expect(page.locator('[data-job-select]')).toHaveCount(15)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  expect((await nextBatch).ok()).toBe(true)
  task = await idle(request, task.id)
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await expect(page.getByText(`第 61-80 条／共 ${task.jobs.length} 条岗位`, { exact: false })).toBeVisible()
  expect(task.jobs.filter((job: any) => !oldKeys.has(job.key)).length).toBeGreaterThan(0)
  for (const source of task.sources) expect(source.count).toBeLessThanOrEqual(40)
  expect(writes.every(path => path.startsWith(api))).toBe(true)
  expect(errors).toEqual([])
  await request.post(`${api}/tasks/${task.id}/stop`)
})

test('收藏快照、JD 编辑幂等、精确资料跳转及删除后重存', async ({ page, request }) => {
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗', ['BOSS'])
  const job = task.jobs[0]
  await select(page, job.key)
  await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
  await page.locator('[data-job-detail]').getByRole('button', { name: '收藏', exact: true }).click()
  await expect.poll(async () => (await (await request.get(`${api}/favorites`)).json()).length).toBe(1)
  const favorite = (await (await request.get(`${api}/favorites`)).json())[0]
  const snapshot = favorite.sources[0].description
  await page.locator('[data-job-detail]').getByRole('button', { name: '保存 JD', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.locator('#job-jd-name').fill('合成搜索验收 JD')
  await page.locator('#job-jd-content').fill('合成搜索验收：用户补充的目标要求。')
  await page.getByRole('button', { name: '保存到资料库', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const link = (await (await request.post(`${api}/jd-links/lookup`, { data: { keys: [job.key] } })).json())[job.key]
  expect(link.contentEdited).toBe(true)
  const duplicate = await request.post(`${api}/jd`, { data: { taskId: task.id, jobKey: job.key, name: '不应覆盖', content: '不应覆盖编辑' } })
  expect((await duplicate.json()).materialId).toBe(link.materialId)
  await page.getByRole('button', { name: '查看已保存 JD', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/materials\\?kind=JD&materialId=${link.materialId}`))
  await expect(page.getByRole('dialog')).toContainText('合成搜索验收 JD')
  await expect(page.getByRole('dialog')).toContainText('合成搜索验收：用户补充的目标要求。')
  await expect(page.getByRole('region', { name: '岗位来源信息' })).toContainText('原站来源：BOSS 直聘')
  await expect(page.getByRole('region', { name: '岗位来源信息' })).toContainText('正文已由用户修改')
  await page.goto('/job-search?tab=favorites')
  await expect(page.locator('[data-job-select]')).toHaveCount(1)
  await select(page, job.key)
  await expect(page.locator('[data-job-detail]')).toContainText(snapshot)
  expect((await (await request.get(`${api}/favorites`)).json())[0]).toEqual(favorite)
  await request.delete(`/api/materials/${link.materialId}`)
  await page.reload(); await select(page, job.key)
  await page.getByRole('button', { name: '保存 JD', exact: true }).click()
  await page.locator('#job-jd-name').fill('合成搜索验收重存 JD')
  await page.getByRole('button', { name: '保存到资料库', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const renewed = (await (await request.post(`${api}/jd-links/lookup`, { data: { keys: [job.key] } })).json())[job.key]
  expect(renewed.materialId).not.toBe(link.materialId)
  await page.locator('[data-job-detail]').getByRole('button', { name: '取消收藏', exact: true }).click()
  await page.getByRole('button', { name: '保留收藏', exact: true }).click()
  await expect(page.locator('[data-job-select]')).toHaveCount(1)
  await page.locator('[data-job-detail]').getByRole('button', { name: '取消收藏', exact: true }).click()
  await page.getByRole('button', { name: '确认取消收藏', exact: true }).click()
  await expect(page.getByText('还没有收藏的岗位', { exact: true })).toBeVisible()
  expect((await (await request.get('/api/materials')).json()).some((material: any) => material.id === renewed.materialId)).toBe(true)
})

test('部分来源失败、空结果、停止后保留结果与重进只恢复条件', async ({ page, request }) => {
  await page.goto('/job-search')
  let task = await start(page, request, '合成部分失败')
  expect(task.sources.find((source: any) => source.source === 'LIEPIN').status).toBe('FAILED')
  await expect(page.getByRole('button', { name: '重试猎聘', exact: true })).toBeVisible()
  expect(task.jobs.length).toBe(35)
  await page.getByRole('tab', { name: '我的收藏', exact: true }).click()
  await page.getByRole('tab', { name: '搜索职位', exact: true }).click()
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await page.goto('/materials')
  await expect.poll(async () => (await (await request.get(`${api}/tasks/${task.id}`)).json()).status).toBe('STOPPED')
  await page.goto('/job-search')
  await expect(page.locator('#job-keyword')).toHaveValue('合成部分失败')
  await expect(page.locator('[data-job-select]')).toHaveCount(0)
  task = await start(page, request, '合成空结果')
  expect(task.jobs).toEqual([])
  expect(task.sources.map((source: any) => source.status)).toEqual(['EMPTY', 'EMPTY', 'EMPTY'])
  await expect(page.getByText('所选平台本次未找到岗位', { exact: true })).toBeVisible()
})

test('超过20条按页展示，移动端分页无页面溢出', async ({ page, request }) => {
  await page.setViewportSize({ width: 375, height: 1000 })
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗')
  expect(task.jobs.length).toBe(55)
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await expect(page.getByRole('button', { name: '加载更多', exact: true })).toHaveCount(0)
  await expect(page.getByText('第 1-20 条／共 55 条岗位', { exact: false })).toBeVisible()
  await page.locator('[data-pagination-page="2"]').click()
  await expect(page.locator('[data-job-select]')).toHaveCount(20)
  await expect(page.getByText('第 21-40 条／共 55 条岗位', { exact: false })).toBeVisible()
  await noOverflow(page, 375)
  await request.post(`${api}/tasks/${task.id}/stop`)
})

test('详情出现登录限制时保留概要，不把截断内容保存为完整正文', async ({ page, request }) => {
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗', ['BOSS'])
  const job = task.jobs.find((item: any) => item.sourceId === 'fixtureboss0015')
  expect(job).toBeTruthy()
  const response = await request.post(`${api}/tasks/${task.id}/jobs/${job.key}/detail`, { data: {} })
  expect(response.status()).toBe(503)
  expect((await response.json()).code).toBe('SESSION_EXPIRED')
  const retained = await (await request.get(`${api}/tasks/${task.id}`)).json()
  expect(retained.jobs.find((item: any) => item.key === job.key).description).toBeNull()
  await request.post(`${api}/tasks/${task.id}/stop`)
})

test('BOSS 临时访问参数只在后端复用，外链去参且正文身份匹配', async ({ page, request }) => {
  await page.setViewportSize({ width: 1440, height: 1400 })
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗', ['BOSS'])
  await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
  const job = task.jobs.find((item: any) => item.sourceId === 'fixtureboss0014')
  const response = await request.post(`${api}/tasks/${task.id}/jobs/${job.key}/detail`, { data: {} })
  expect(response.ok()).toBe(true)
  const detail = await response.json()
  expect(detail.key).toBe(job.key)
  expect(detail.url).toBe(job.url)
  expect(JSON.stringify(detail)).not.toContain('synthetic-fixtureboss0014')
  expect(detail.description).toContain('【合成来源身份：fixtureboss0014】')
  await request.post(`${api}/tasks/${task.id}/stop`)
})

test('智联按实际正文容器读取完整内容并保持具体岗位身份', async ({ page, request }) => {
  await page.setViewportSize({ width: 375, height: 1000 })
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗', ['ZHAOPIN'])
  const job = task.jobs[0]
  const response = await request.post(`${api}/tasks/${task.id}/jobs/${job.key}/detail`, { data: {} })
  expect(response.ok()).toBe(true)
  const detail = await response.json()
  expect(detail.key).toBe(job.key)
  expect(detail.description).toContain('【合成测试正文】')
  await request.post(`${api}/tasks/${task.id}/stop`)
})

for (const width of [375, 768, 820, 1024, 1280, 1440]) {
  test(`合成真实链路在 ${width}px 的列表、详情、JD 预览无页面溢出`, async ({ page, request }) => {
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/job-search')
    const task = await start(page, request, '合成技术岗', ['BOSS'])
    await noOverflow(page, width); await capture(page, `list-${width}`)
    // 宽屏会自动读取首条正文；等待这一操作完成，避免与本例的手动切换竞争同一来源。
    if (await page.locator('[data-job-detail]').isVisible()) await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
    const key = task.jobs[2].key
    await select(page, key)
    await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
    await noOverflow(page, width); await capture(page, `detail-${width}`)
    await page.locator('[data-job-detail]').getByRole('button', { name: '保存 JD', exact: true }).click()
    await expect(page.locator('#job-jd-content')).toHaveValue(/【合成测试正文】/)
    await noOverflow(page, width); await capture(page, `jd-${width}`)
    await page.getByRole('button', { name: '取消', exact: true }).click()
    if (await page.getByRole('button', { name: '返回列表', exact: true }).isVisible()) {
      await page.getByRole('button', { name: '返回列表', exact: true }).click()
      await expect(page.locator(`[data-job-select="${key}"]`)).toBeFocused()
    }
    expect(errors).toEqual([])
    await request.post(`${api}/tasks/${task.id}/stop`)
  })
}

test('侧栏、工作区临界宽度与低高度切换保持稳定且详情可返回', async ({ page, request }) => {
  await page.setViewportSize({ width: 1440, height: 1400 })
  await page.goto('/job-search')
  const task = await start(page, request, '合成技术岗', ['BOSS'])
  const workspace = page.locator('.job-workspace')
  await expect(workspace).toHaveClass(/dual/)
  expect(await page.locator('#job-search-platform-BOSS').evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(24)
  await page.getByRole('button', { name: '收起侧边栏', exact: true }).click()
  await expect(workspace).toHaveClass(/dual/)
  async function setWorkspaceWidth(wanted: number) {
    // 侧栏、页面内边距和最大宽度共同影响工作区，按真实尺寸有界校准视口。
    for (let attempt = 0; attempt < 4; attempt++) {
      const width = await workspace.evaluate(element => element.getBoundingClientRect().width)
      if (Math.abs(width - wanted) <= 0.5) break
      await page.setViewportSize({ width: page.viewportSize()!.width + Math.round(wanted - width), height: 1400 })
      await page.evaluate(() => new Promise<void>(done => requestAnimationFrame(() => done())))
    }
    await expect.poll(async () => Math.abs(await workspace.evaluate(element => element.getBoundingClientRect().width) - wanted)).toBeLessThanOrEqual(0.5)
  }
  await setWorkspaceWidth(959)
  await expect(workspace).not.toHaveClass(/dual/)
  await setWorkspaceWidth(961)
  await expect(workspace).toHaveClass(/dual/)
  await page.setViewportSize({ width: 1440, height: 1400 })
  await expect.poll(() => workspace.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(961)
  const top = await workspace.evaluate(element => element.getBoundingClientRect().top + window.scrollY)
  await page.setViewportSize({ width: 1440, height: Math.floor(top + 24 + 359) })
  await expect(workspace).not.toHaveClass(/dual/)
  const key = task.jobs[1].key
  await select(page, key)
  await expect(page.locator('[data-job-detail]')).toContainText('【合成测试正文】')
  await expect(workspace).not.toHaveClass(/dual/)
  await page.getByRole('button', { name: '返回列表', exact: true }).click()
  await expect(page.locator(`[data-job-select="${key}"]`)).toBeFocused()
  await expect(workspace).not.toHaveClass(/dual/)
  await capture(page, 'low-height-stable')
  await page.setViewportSize({ width: 1440, height: 1400 })
  await expect(workspace).toHaveClass(/dual/)
  await noOverflow(page, 1440)
  await request.post(`${api}/tasks/${task.id}/stop`)
})
