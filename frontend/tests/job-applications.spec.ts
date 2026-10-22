import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { mkdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const api = '/api/job-applications'
const screens = resolve('../.evidence/job-applications/screens')
async function list(request: APIRequestContext) { const result = await request.get(`${api}?archived=all&sort=createdAt&direction=asc`); expect(result.ok()).toBe(true); return result.json() }
async function create(request: APIRequestContext, data: Record<string, unknown>) { const result = await request.post(api, { data }); expect(result.ok()).toBe(true); return result.json() }
async function capture(page: Page, name: string) { await mkdir(screens, { recursive: true }); await page.screenshot({ path: resolve(screens, `${name}.png`), fullPage: await page.getByRole('dialog').count() === 0, animations: 'disabled' }) }
async function noOverflow(page: Page, width: number) { await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(width) }
test.beforeEach(async ({ request }) => { for (const job of await list(request)) expect((await request.delete(`${api}/${job.id}`)).ok()).toBe(true) })

test('从导航新增同公司双岗位、全字段编辑、七状态、归档恢复和确认删除', async ({ page, request }) => {
  const errors: string[] = [], writes: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (request.method() !== 'GET') writes.push(`${request.method()} ${new URL(request.url()).pathname}`) })
  await page.goto('/')
  await page.getByRole('navigation', { name: '主导航', exact: true }).getByRole('link', { name: '求职管理' }).click()
  await expect(page).toHaveURL('/job-applications')
  await expect(page.getByText('还没有岗位记录')).toBeVisible()
  await page.getByRole('button', { name: '新增岗位', exact: true }).click()
  await page.locator('#job-company').fill('合成星河科技'); await page.locator('#job-position').fill('Java 后端开发')
  await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: '新增岗位', exact: true }).click()
  await page.locator('#job-company').fill('合成星河科技'); await page.locator('#job-position').fill('测试开发')
  await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(2)
  const first = (await list(request)).find((row: any) => row.position === 'Java 后端开发')
  const row = page.locator(`[data-job-id="${first.id}"]`)
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  await page.locator('#job-location').fill('上海 · 徐汇'); await page.locator('#job-platform').fill('公司官网'); await page.locator('#job-type').selectOption('FULL_TIME')
  await page.locator('#job-url').fill('https://example.com/jobs/123'); await page.locator('#job-jd').fill('负责 Java 服务, SQL\n理解"事务"与故障恢复')
  await page.locator('#job-applied').fill('2026-10-21'); await page.locator('#job-next').fill('2026-10-25')
  await page.locator('#job-interview').fill('2026-10-24T10:30'); await page.locator('#job-interview-note').fill('技术面，准备项目复盘')
  await page.locator('#job-notes').fill('沟通记录\n下一步准备系统设计')
  await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.reload(); await expect(row).toContainText('上海 · 徐汇')
  let stored = await (await request.get(`${api}/${first.id}`)).json()
  expect(stored.interviewAt).toBe('2026-10-24T02:30:00Z'); expect(stored.appliedDate).toBe('2026-10-21'); expect(stored.notes).toContain('\n')
  for (const state of ['APPLIED', 'ASSESSMENT', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN', 'TO_APPLY']) {
    const response = page.waitForResponse(r => r.url().endsWith(`${first.id}/status`) && r.request().method() === 'PATCH')
    await row.locator('[data-job-status]').selectOption(state); expect((await response).ok()).toBe(true)
    await expect(row.locator('[data-job-status]')).toHaveValue(state)
  }
  await row.locator('[data-job-detail]').click(); await expect(page.getByRole('dialog')).toContainText('2026-10-24 10:30')
  await expect(page.getByRole('dialog')).toContainText('负责 Java 服务, SQL')
  const countBeforeLink = writes.length
  await page.context().route('https://example.com/jobs/123', route => route.fulfill({ contentType: 'text/html', body: '<p>合成外部岗位页面</p>' }))
  const popup = page.waitForEvent('popup'); await page.getByRole('link', { name: '打开原始岗位链接' }).click(); await (await popup).close()
  expect(writes.length).toBe(countBeforeLink)
  await page.getByRole('button', { name: '关闭弹窗' }).click()
  await row.getByRole('button', { name: '归档', exact: true }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(1)
  await page.getByRole('button', { name: '已归档', exact: true }).click(); await expect(row).toBeVisible(); await expect(row.locator('[data-job-status]')).toHaveValue('TO_APPLY')
  await row.getByRole('button', { name: '恢复', exact: true }).click(); await expect(page.getByText('还没有归档岗位')).toBeVisible()
  await page.getByRole('button', { name: '未归档', exact: true }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(2)
  await row.getByRole('button', { name: '删除', exact: true }).click(); await page.getByRole('button', { name: '取消', exact: true }).click(); await expect(row).toBeVisible()
  await row.getByRole('button', { name: '删除', exact: true }).click(); await page.getByRole('button', { name: '确认删除' }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(1)
  expect((await list(request))[0].position).toBe('测试开发'); expect(errors).toEqual([])
})

test('真实查询搜索筛选排序、空结果与清除筛选', async ({ page, request }) => {
  await create(request, { company: '合成检索%公司', position: '晚行动', status: 'APPLIED', nextActionDate: '2026-11-03' })
  await create(request, { company: '合成检索%公司', position: '早行动', status: 'INTERVIEW', nextActionDate: '2026-11-01' })
  await create(request, { company: '其他公司', position: '空日期' })
  await page.goto('/job-applications'); await page.getByLabel('日期排序').selectOption('nextActionDate:asc')
  await expect(page.locator('[data-job-detail] span')).toHaveText(['早行动', '晚行动', '空日期'])
  await page.getByLabel('日期排序').selectOption('nextActionDate:desc'); await expect(page.locator('[data-job-detail] span')).toHaveText(['晚行动', '早行动', '空日期'])
  await page.getByLabel('搜索公司').fill('检索%'); await expect(page.locator('[data-job-id]')).toHaveCount(2)
  await page.locator('#job-filter-status').selectOption('INTERVIEW'); await expect(page.locator('[data-job-detail] span')).toHaveText(['早行动'])
  await page.getByLabel('搜索公司').fill('不存在的公司'); await expect(page.getByText('没有匹配的岗位')).toBeVisible()
  await page.getByRole('button', { name: '清除筛选' }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(3)
})

test('CSV 真实下载、完整恢复、重复跳过与错误预览不写入', async ({ page, request }) => {
  const first = await create(request, { company: '=合成公式公司', position: '带,逗号与"引号"', jd: '第一行\n第二行', notes: '文本:原始前缀\n+备注', jobType: 'CAMPUS', status: 'OFFER', interviewAt: '2026-10-23T10:00:00+08:00' })
  expect((await request.patch(`${api}/${first.id}/archive`, { data: { archived: true } })).ok()).toBe(true)
  await create(request, { company: '合成公司', position: '第二岗位' })
  const before = await list(request)
  await page.goto('/job-applications')
  const waitDownload = page.waitForEvent('download'); await page.getByRole('button', { name: '导出全部' }).click()
  const download = await waitDownload; const bytes = await readFile((await download.path())!); expect(bytes.toString()).toContain('文本:=合成公式公司')
  for (const record of before) await request.delete(`${api}/${record.id}`)
  await page.reload(); await page.locator('[data-job-file]').setInputFiles({ name: '恢复.csv', mimeType: 'text/csv', buffer: bytes })
  await expect(page.getByRole('region', { name: 'CSV 导入预览' })).toContainText('共 2 条'); expect(await list(request)).toHaveLength(0)
  await page.locator('[data-job-import-confirm]').click(); await expect(page.getByText('已导入 2 条岗位，跳过 0 条重复记录')).toBeVisible()
  expect(await list(request)).toEqual(before)
  await page.locator('[data-job-file]').setInputFiles({ name: '重复.csv', mimeType: 'text/csv', buffer: bytes })
  await expect(page.getByText('全部记录已存在，无需重复导入。')).toBeVisible(); await expect(page.locator('[data-job-import-confirm]')).toBeDisabled()
  const invalid = bytes.toString().replace('投递记录-1', '格式错误')
  await page.locator('[data-job-file]').setInputFiles({ name: '错误.csv', mimeType: 'text/csv', buffer: Buffer.from(invalid) })
  await expect(page.getByText('存在 1 行错误', { exact: false })).toBeVisible(); await expect(page.locator('[data-job-import-confirm]')).toHaveCount(0)
  expect(await list(request)).toEqual(before)
})

for (const width of [375, 768, 1024, 1440]) {
  test(`真实记录列表、详情和录入在 ${width}px 可用`, async ({ page, request }) => {
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await create(request, { company: '合成星河科技', position: 'Java 后端开发工程师', location: '上海 · 徐汇', platform: '公司官网', status: 'INTERVIEW', nextActionDate: '2026-10-25', jd: '负责交易系统与数据服务。\n'+ '验证长正文换行。'.repeat(80), notes: '准备项目复盘、事务与索引相关问题。' })
    await create(request, { company: '合成蓝杉数据', position: '平台研发工程师', location: '杭州', platform: 'BOSS', status: 'APPLIED', appliedDate: '2026-10-21' })
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/job-applications'); await expect(page.locator('[data-job-id]')).toHaveCount(2)
    await noOverflow(page, width); await capture(page, `table-${width}`)
    await page.locator('[data-job-detail]').filter({ hasText: '合成星河科技' }).click(); await expect(page.getByRole('dialog')).toContainText('验证长正文换行')
    await noOverflow(page, width); await capture(page, `detail-${width}`)
    await page.getByRole('button', { name: '编辑岗位', exact: true }).click(); await expect(page.locator('#job-company')).toHaveValue('合成星河科技')
    await page.locator('#job-notes').fill('手机也可编辑'); await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.getByRole('button', { name: '新增岗位', exact: true }).click(); await page.locator('#job-company').fill('合成新增公司'); await page.locator('#job-position').fill('实习岗位')
    if (width <= 768) {
      expect(await page.locator('#job-company').evaluate(e => getComputedStyle(e).fontSize)).toBe('16px')
      expect((await page.getByRole('button', { name: '保存岗位' }).boundingBox())!.height).toBeGreaterThanOrEqual(44)
    }
    await noOverflow(page, width); await capture(page, `form-${width}`)
    await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.locator('[data-job-id]')).toHaveCount(3)
    expect(errors).toEqual([])
  })
}

test('加载与保存失败保留输入，保存中禁止重复与关闭', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let count = 0
  await page.route('**/api/job-applications', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    count++; if (count === 1) { await gate; return route.fulfill({ status: 500, json: { message: '合成磁盘写入失败' } }) }
    return route.continue()
  })
  await page.goto('/job-applications'); await page.getByRole('button', { name: '新增岗位', exact: true }).click()
  await page.locator('#job-company').fill('合成错误恢复'); await page.locator('#job-position').fill('后端岗位')
  await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.getByRole('button', { name: '正在保存…' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '关闭弹窗' })).toBeDisabled(); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toBeVisible()
  release(); await expect(page.getByRole('dialog').getByRole('alert')).toContainText('合成磁盘写入失败'); await expect(page.locator('#job-company')).toHaveValue('合成错误恢复')
  await page.getByRole('button', { name: '保存岗位' }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); await expect(page.locator('[data-job-id]')).toHaveCount(1); expect(count).toBe(2)
})
