import { afterEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import JobSearch from './JobSearch.vue'
import type { JobRecord, TaskSnapshot } from '../../api/job-search'
const job = (source = 'BOSS', changes: Partial<JobRecord> = {}): JobRecord => ({ key: `${source}:same`, source, sourceId: 'same', title: '测试后端岗位', company: '合成公司', city: '深圳', location: '南山', url: 'https://www.zhipin.com/job_detail/test.html', salaryText: '15–25k', salaryMinK: 15, salaryMaxK: 25, salaryMonths: 13, experienceText: '3–10年', educationText: '本科', description: '合成正文', fetchedAt: '2026-10-23T00:00:00Z', ...changes } as JobRecord)
const snapshot = (changes: Partial<TaskSnapshot> = {}): TaskSnapshot => ({ id: 't1', keyword: 'Java', cityId: 'sz', cityName: '深圳', status: 'IDLE', revision: 1, sources: [{ source: 'BOSS', status: 'READY', count: 1, hasMore: false }], jobs: [job()], ...changes })
const respond = (value: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(value), { status })
let component: VueWrapper, body: DOMWrapper<HTMLElement>
let router: ReturnType<typeof createRouter>
let unexpected: string[] = []
function button(text: string) { const target = body.findAll('button').find(item => item.text().trim() === text); expect(target, `应有“${text}”按钮`).toBeDefined(); return target! }
function api(handler: (url: string, options?: RequestInit) => Response | Promise<Response> | undefined) {
  vi.stubGlobal('fetch', vi.fn((url: string, options?: RequestInit) => {
    const response = handler(url, options); if (response) return Promise.resolve(response)
    if (url === '/api/job-search/cities' && !options?.method) return Promise.resolve(respond([{ id: 'sz', name: '深圳' }]))
    if (url === '/api/job-search/favorites' && !options?.method) return Promise.resolve(respond([]))
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return Promise.resolve(respond(Object.fromEntries(JSON.parse(String(options.body)).keys.map((key: string) => [key, null]))))
    if (url.endsWith('/stop') && options?.method === 'POST') return Promise.resolve(respond(snapshot({ status: 'STOPPED' })))
    unexpected.push(`${url} ${options?.method || 'GET'}`)
    throw new Error(`未知请求：${url} ${options?.method}`)
  }))
}
async function render(path = '/job-search', width = 1440) {
  vi.stubGlobal('innerWidth', width); vi.stubGlobal('innerHeight', 1000); vi.stubGlobal('scrollTo', vi.fn())
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/job-search', component: JobSearch }, { path: '/materials', component: { template: '<div>资料库</div>' } }] })
  await router.push(path); await router.isReady()
  component = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [router] } }); body = new DOMWrapper(document.body); await flushPromises()
}
async function search() { await body.get('#job-keyword').setValue('Java'); await body.get('#job-city').setValue('深圳'); await body.get('#job-search-form').trigger('submit'); await flushPromises() }
afterEach(() => { component?.unmount(); document.body.innerHTML = ''; localStorage.clear(); vi.useRealTimers(); vi.unstubAllGlobals(); const unknown = unexpected; unexpected = []; expect(unknown).toEqual([]) })
it('首次不预填、不自动查询；搜索条件传递三站，草稿修改不改本次摘要', async () => {
  let calls = 0
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') { calls++; expect(JSON.parse(String(options.body))).toEqual({ keyword: 'Java', cityId: 'sz', sources: ['BOSS', 'LIEPIN', 'ZHAOPIN'] }); return respond(snapshot()) } })
  await render(); expect(calls).toBe(0); expect((body.get('#job-keyword').element as HTMLInputElement).value).toBe('')
  await search(); expect(calls).toBe(1); expect(body.text()).toContain('本次：Java／深圳')
  await body.get('#job-keyword').setValue('新关键词'); expect(body.text()).toContain('条件已修改，点击搜索后生效'); expect(body.text()).toContain('本次：Java／深圳')
})
it('部分失败保留结果，同 ID 不同来源独立；单站重试路径准确', async () => {
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('LIEPIN', { description: '另一个岗位正文' })], sources: [{ source: 'BOSS', status: 'READY', count: 1, hasMore: false }, { source: 'LIEPIN', status: 'FAILED', count: 1, hasMore: false, errorMessage: '访问配置需更新' }] }))
    if (url === '/api/job-search/tasks/t1/retry' && options?.method === 'POST') { expect(JSON.parse(String(options.body))).toEqual({ source: 'LIEPIN' }); return respond(snapshot({ jobs: [job(), job('LIEPIN', { description: '另一个岗位正文' })] })) }
  })
  await render(); await search(); expect(body.findAll('[data-job-select]')).toHaveLength(2); expect(body.text()).toContain('访问配置需更新')
  await button('重试猎聘').trigger('click'); await flushPromises(); expect(body.findAll('[data-job-select]')).toHaveLength(2)
})
it('岗位超过20条后按20条分页，不再显示加载更多', async () => {
  const jobs = Array.from({ length: 21 }, (_, index) => job('BOSS', { key: `BOSS:${index}`, sourceId: String(index), title: `岗位${index + 1}` }))
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs, sources: [{ source: 'BOSS', status: 'READY', count: 21, hasMore: false }] })) })
  await render(); await search()
  expect(body.findAll('[data-job-select]')).toHaveLength(20)
  expect(body.text()).toContain('第 1-20 条／共 21 条岗位')
  expect(body.text()).not.toContain('加载更多')
  await body.get('[data-pagination-page="2"]').trigger('click'); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(1)
  expect(body.get('[data-job-select]').attributes('data-job-select')).toBe('BOSS:20')
  expect(body.text()).toContain('第 21-21 条／共 21 条岗位')
  await body.get('[aria-label="上一页"]').trigger('click'); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(20)
})
it('停止后忽略迟到轮询，发起新条件后旧结果不混入', async () => {
  vi.useFakeTimers(); let resolvePoll!: (value: Response) => void; let starts = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(++starts === 1 ? snapshot({ status: 'RUNNING', sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }] }) : snapshot({ id: 't2', keyword: '新关键词', jobs: [job('BOSS', { title: '新岗位' })] }))
    if (url === '/api/job-search/tasks/t1' && !options?.method) return new Promise<Response>(resolve => { resolvePoll = resolve })
  })
  await render(); await search(); await vi.advanceTimersByTimeAsync(1100)
  await button('停止本次搜索').trigger('click'); await flushPromises()
  await body.get('#job-keyword').setValue('新关键词'); await body.get('#job-search-form').trigger('submit'); await flushPromises()
  resolvePoll(respond(snapshot({ jobs: [job('BOSS', { title: '迟到旧岗位' })] }))); await flushPromises()
  expect(body.text()).toContain('新岗位'); expect(body.text()).not.toContain('迟到旧岗位')
})
it('筛选排除当前详情则清空，不自动选另一项；原文选项不随筛选消失', async () => {
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('LIEPIN', { title: '另一项', experienceText: '不限', description: '不同正文' })] })) })
  await render(); await search(); expect(body.find('[data-job-detail]').exists()).toBe(true)
  await body.get('#job-experience').setValue('不限'); await flushPromises()
  expect(body.find('[data-job-detail]').exists()).toBe(false); expect(body.text()).toContain('选择一个岗位查看详情'); expect(body.get('#job-experience').text()).toContain('3–10年')
})
it('岗位类型筛选可区分日常实习、暑期实习和正式岗位，并保留未知类型', async () => {
  const jobs = [
    job('BOSS', { key: 'BOSS:daily', sourceId: 'daily', title: '日常实习', description: '日常正文', jobType: 'DAILY_INTERNSHIP' }),
    job('LIEPIN', { key: 'LIEPIN:summer', sourceId: 'summer', title: '暑期实习', description: '暑期正文', jobType: 'SUMMER_INTERNSHIP' }),
    job('ZHAOPIN', { key: 'ZHAOPIN:full', sourceId: 'full', title: '正式岗位', description: '正式正文', jobType: 'FULL_TIME' }),
    job('BOSS', { key: 'BOSS:unknown', sourceId: 'unknown', title: '类型未知', description: '未知正文', jobType: 'UNKNOWN' }),
  ]
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs, sources: [{ source: 'BOSS', status: 'READY', count: 2, hasMore: false }, { source: 'LIEPIN', status: 'READY', count: 1, hasMore: false }, { source: 'ZHAOPIN', status: 'READY', count: 1, hasMore: false }] })) })
  await render(); await search()
  expect(body.findAll('[data-job-select]')).toHaveLength(4)
  await body.get('#job-type').setValue('DAILY_INTERNSHIP'); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(2)
  expect(body.text()).toContain('日常实习'); expect(body.text()).toContain('类型未知'); expect(body.text()).not.toContain('暑期实习概要'); expect(body.text()).not.toContain('正式岗位概要')
  await body.get('#job-type').setValue('SUMMER_INTERNSHIP'); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(2)
  expect(body.text()).toContain('暑期实习'); expect(body.text()).toContain('类型未知'); expect(body.text()).not.toContain('日常实习概要')
  await body.get('#job-type').setValue('FULL_TIME'); await body.get('#job-unknown-type').setValue(false); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(1); expect(body.text()).toContain('正式岗位')
})
it('收藏读取本地快照，不抓详情；切页签保留运行状态', async () => {
  let detail = 0
  api((url, options) => {
    if (url === '/api/job-search/favorites' && !options?.method) return respond([{ id: 'f1', createdAt: '2026-10-23T00:00:00Z', sources: [job('BOSS', { description: null })] }])
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ status: 'RUNNING', sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }] }))
    if (url.includes('/detail')) { detail++; return respond(job()) }
  })
  await render(); await search(); await button('我的收藏').trigger('click'); await flushPromises()
  expect(body.text()).toContain('搜索仍在进行'); expect(body.text()).toContain('收藏时未获取正文'); expect(detail).toBe(0)
})
it('整组收藏补充只传新来源，成功才更新；取消收藏必须确认', async () => {
  let removed = false
  api((url, options) => {
    if (url === '/api/job-search/favorites' && !options?.method) return respond([{ id: 'f1', createdAt: '2026-10-22T00:00:00Z', sources: [job()] }])
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('LIEPIN')] }))
    if (url === '/api/job-search/favorites' && options?.method === 'POST') { expect(JSON.parse(String(options.body))).toEqual({ taskId: 't1', jobKeys: ['LIEPIN:same'], favoriteId: 'f1' }); return respond({ id: 'f1', createdAt: '2026-10-22T00:00:00Z', sources: [job(), job('LIEPIN')] }) }
    if (url === '/api/job-search/favorites/f1' && options?.method === 'DELETE') { removed = true; return respond(null, 204) }
  })
  await render(); await search(); await body.get('[data-job-favorite]').trigger('click'); await flushPromises(); expect(body.text()).toContain('已收藏 2/2 来源')
  await body.get('[data-cancel-favorite]').trigger('click'); await flushPromises(); expect(removed).toBe(false); expect(body.get('[role="dialog"]').text()).toContain('已保存 JD 保留')
  await button('确认取消收藏').trigger('click'); await flushPromises(); expect(removed).toBe(true)
})
it('JD 预览固定来源，保存失败保留编辑；未知关联禁止重复创建并可重试', async () => {
  let lookupFails = true, saves = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot())
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return lookupFails ? respond({ message: '保存状态读取失败' }, 500) : respond({ 'BOSS:same': null })
    if (url === '/api/job-search/jd' && options?.method === 'POST') { saves++; expect(JSON.parse(String(options.body))).toMatchObject({ taskId: 't1', jobKey: 'BOSS:same', name: '我编辑的名称', content: '我编辑的正文' }); return respond({ message: '保存失败' }, 500) }
  })
  await render(); await search(); expect(body.text()).toContain('保存状态暂不可用'); expect(body.find('#job-jd-form').exists()).toBe(false)
  lookupFails = false; await button('重试保存状态').trigger('click'); await flushPromises(); await button('保存 JD').trigger('click'); await flushPromises()
  await body.get('#job-jd-name').setValue('我编辑的名称'); await body.get('#job-jd-content').setValue('我编辑的正文'); await body.get('#job-jd-form').trigger('submit'); await flushPromises()
  expect(saves).toBe(1); expect(body.get('[role="dialog"]').text()).toContain('保存失败'); expect((body.get('#job-jd-content').element as HTMLTextAreaElement).value).toBe('我编辑的正文')
})
it('已保存 JD 精确跳转对应资料并停止搜索，已删除关联可重新预览', async () => {
  const link = { materialId: 'jd/same', source: 'BOSS', sourceId: 'same', url: job().url, fetchedAt: job().fetchedAt, contentEdited: false }
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot())
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return respond({ 'BOSS:same': link })
  })
  await render(); await search(); await button('查看已保存 JD').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/materials'); expect(router.currentRoute.value.query).toEqual({ kind: 'JD', materialId: 'jd/same' })
})
it('恢复支持城市的 ID 映射，不自动搜索且未修改时不误报条件变化', async () => {
  let starts = 0
  localStorage.setItem('job-search:draft', JSON.stringify({ keyword: 'Java', cityId: 'sz', sources: ['BOSS'] }))
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') { starts++; expect(JSON.parse(String(options.body)).cityId).toBe('sz'); return respond(snapshot()) } })
  await render(); expect(starts).toBe(0); expect((body.get('#job-city').element as HTMLInputElement).value).toBe('深圳')
  await body.get('#job-search-form').trigger('submit'); await flushPromises(); expect(starts).toBe(1); expect(body.text()).not.toContain('条件已修改')
  expect(JSON.parse(localStorage.getItem('job-search:draft')!).cityId).toBe('sz')
})
it('城市选择器支持展开、键盘确认和清除', async () => {
  api(() => undefined)
  await render(); const input = body.get('#job-city')
  await input.trigger('focus'); expect(body.get('[role="listbox"]').text()).toContain('深圳')
  await input.trigger('keydown', { key: 'ArrowDown' }); await input.trigger('keydown', { key: 'Enter' }); await flushPromises()
  expect((input.element as HTMLInputElement).value).toBe('深圳'); expect(body.find('[role="listbox"]').exists()).toBe(false)
  await body.get('[aria-label="清除城市"]').trigger('click'); expect((input.element as HTMLInputElement).value).toBe('')
})
it('缺少关键词、未知城市与未选来源就近说明且不发请求', async () => {
  let starts = 0; api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') { starts++; return respond(snapshot()) } })
  await render(); await body.get('#job-search-form').trigger('submit'); expect(body.text()).toContain('请填写岗位关键词')
  await body.get('#job-keyword').setValue('Java'); await body.get('#job-city').setValue('虚构城市'); await body.get('#job-search-form').trigger('submit'); expect(body.text()).toContain('请选择支持的城市')
  await body.get('#job-city').setValue('深圳'); for (const source of ['BOSS', 'LIEPIN', 'ZHAOPIN']) await body.get(`#job-search-platform-${source}`).setValue(false)
  await body.get('#job-search-form').trigger('submit'); expect(body.text()).toContain('请至少选择一个搜索平台'); expect(starts).toBe(0)
})
it('相同来源 ID 的旧任务详情迟到不会写入新任务', async () => {
  let resolveDetail!: (value: Response) => void, starts = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(++starts === 1 ? snapshot({ jobs: [job('BOSS', { description: null })] }) : snapshot({ id: 't2', jobs: [job('BOSS', { title: '新任务岗位', description: '新任务正文' })] }))
    if (url === '/api/job-search/tasks/t1/jobs/BOSS%3Asame/detail' && options?.method === 'POST') return new Promise<Response>(resolve => { resolveDetail = resolve })
  })
  await render(); await search(); expect(body.text()).toContain('正在读取当前来源正文')
  await body.get('#job-search-form').trigger('submit'); await flushPromises(); resolveDetail(respond(job('BOSS', { description: '旧任务迟到正文' }))); await flushPromises()
  expect(body.get('[data-job-detail]').text()).toContain('新任务正文'); expect(body.text()).not.toContain('旧任务迟到正文')
})
it('选择另一个岗位后迟到详情不串岗，当前来源可独立取得正文', async () => {
  let resolveFirst!: (value: Response) => void
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job('BOSS', { description: null }), job('LIEPIN', { title: '第二来源岗位', description: null })] }))
    if (url === '/api/job-search/tasks/t1/jobs/BOSS%3Asame/detail') return new Promise<Response>(resolve => { resolveFirst = resolve })
    if (url === '/api/job-search/tasks/t1/jobs/LIEPIN%3Asame/detail') return respond(job('LIEPIN', { title: '第二来源岗位', description: '第二来源的完整正文' }))
  })
  await render(); await search(); await body.get('[data-job-select="LIEPIN:same"]').trigger('click'); await flushPromises(); resolveFirst(respond(job('BOSS', { description: '第一来源迟到正文' }))); await flushPromises()
  expect(body.get('[data-job-detail]').text()).toContain('第二来源的完整正文'); expect(body.get('[data-job-detail]').text()).not.toContain('第一来源迟到正文')
})
it('收藏快照可直接补正文保存 JD，提交冻结 favoriteId 和来源身份', async () => {
  let saveInput: unknown
  api((url, options) => {
    if (url === '/api/job-search/favorites' && !options?.method) return respond([{ id: 'f1', createdAt: job().fetchedAt, sources: [job('BOSS', { description: null }), job('LIEPIN', { description: null })] }])
    if (url === '/api/job-search/jd' && options?.method === 'POST') { saveInput = JSON.parse(String(options.body)); return respond({ materialId: 'jd1', created: true, source: { source: 'BOSS', sourceId: 'same', url: job().url, fetchedAt: job().fetchedAt, contentEdited: true } }) }
  })
  await render('/job-search?tab=favorites'); await button('保存 JD').trigger('click'); await flushPromises(); expect(body.get('[role="dialog"]').text()).toContain('未获取岗位正文，请补充后保存')
  await body.get('#job-jd-content').setValue('我手工补充的完整正文'); await body.get('#job-jd-form').trigger('submit'); await flushPromises()
  expect(saveInput).toEqual({ favoriteId: 'f1', jobKey: 'BOSS:same', name: '合成公司－测试后端岗位', content: '我手工补充的完整正文' }); expect(body.find('[role="dialog"]').exists()).toBe(false); expect(body.text()).toContain('查看已保存 JD')
})
it('超长名称和正文完整保留，关闭编辑需要确认，保存中禁止重复提交', async () => {
  let saves = 0, resolveSave!: (value: Response) => void
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job('BOSS', { title: '岗'.repeat(130), description: '文'.repeat(24001) })] }))
    if (url === '/api/job-search/jd' && options?.method === 'POST') { saves++; return new Promise<Response>(resolve => { resolveSave = resolve }) }
  })
  await render(); await search(); await button('保存 JD').trigger('click'); await flushPromises()
  expect((body.get('#job-jd-name').element as HTMLInputElement).value.length).toBeGreaterThan(120); expect((body.get('#job-jd-content').element as HTMLTextAreaElement).value.length).toBe(24001)
  await body.get('#job-jd-form').trigger('submit'); await flushPromises(); expect(saves).toBe(0); expect(body.text()).toContain('名称超出')
  await body.get('#job-jd-name').setValue('合法名称'); await body.get('#job-jd-form').trigger('submit'); expect(body.text()).toContain('正文超出 1 个字符')
  const confirm = vi.fn(() => false); vi.stubGlobal('confirm', confirm); await button('取消').trigger('click'); await flushPromises(); expect(confirm).toHaveBeenCalledOnce(); expect(body.find('[role="dialog"]').exists()).toBe(true)
  await body.get('#job-jd-content').setValue('合法正文'); await body.get('#job-jd-form').trigger('submit'); await body.get('#job-jd-form').trigger('submit'); expect(saves).toBe(1)
  resolveSave(respond({ materialId: 'jd1', created: true, source: { source: 'BOSS', sourceId: 'same', url: job().url, fetchedAt: job().fetchedAt, contentEdited: true } })); await flushPromises()
})
it('已删除 JD 关联刷新为空时恢复预览而不跳到旧资料', async () => {
  let lookups = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot())
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return respond({ 'BOSS:same': ++lookups === 1 ? { materialId: 'old-jd', source: 'BOSS', sourceId: 'same', url: job().url, fetchedAt: job().fetchedAt, contentEdited: false } : null })
  })
  await render(); await search(); await button('查看已保存 JD').trigger('click'); await flushPromises(); expect(router.currentRoute.value.path).toBe('/job-search'); expect(body.find('#job-jd-form').exists()).toBe(true)
})
it('分组筛选只在一个来源同时满足；保留不匹配来源并整组收藏', async () => {
  let keys: string[] = []
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job('BOSS', { educationText: '大专' }), job('LIEPIN', { salaryMinK: 8, salaryMaxK: 12 })] }))
    if (url === '/api/job-search/favorites' && options?.method === 'POST') { keys = JSON.parse(String(options.body)).jobKeys; return respond({ id: 'f1', createdAt: job().fetchedAt, sources: [job(), job('LIEPIN')] }) }
  })
  await render(); await search(); await body.get('[aria-label="最低月薪"]').setValue('20'); await body.get('[aria-label="最低月薪"]').trigger('blur'); await body.get('#job-education').setValue('本科'); await flushPromises()
  expect(body.findAll('[data-job-select]')).toHaveLength(0); expect(body.text()).toContain('当前筛选无匹配')
  await body.get('[aria-label="最低月薪"]').setValue(''); await body.get('[aria-label="最低月薪"]').trigger('blur'); await flushPromises(); await body.get('[data-job-select]').trigger('click'); await flushPromises()
  expect(body.get('[data-job-detail]').text()).toContain('未匹配当前筛选'); await body.get('[data-job-favorite]').trigger('click'); await flushPromises(); expect(keys).toEqual(['BOSS:same', 'LIEPIN:same'])
})
it('窄屏进入详情后返回恢复原行焦点与页面位置，系统返回也恢复', async () => {
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('LIEPIN', { title: '第二岗位', description: '另一个正文' })] })) })
  await render('/job-search', 768); await search(); expect(body.find('[data-job-detail]').exists()).toBe(false)
  vi.stubGlobal('scrollY', 315); const select = body.get('[data-job-select="LIEPIN:same"]'); await select.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query.job).toBe('LIEPIN:same'); expect(document.activeElement?.id).toBe('job-detail-title')
  await button('返回列表').trigger('click'); await flushPromises(); expect(document.activeElement?.getAttribute('data-job-select')).toBe('LIEPIN:same'); expect(window.scrollTo).toHaveBeenLastCalledWith({ top: 315, behavior: 'instant' })
  await select.trigger('click'); await flushPromises(); router.back(); await flushPromises(); await new Promise(resolve => setTimeout(resolve, 0)); await flushPromises(); expect(document.activeElement?.getAttribute('data-job-select')).toBe('LIEPIN:same')
})
it('剩余高度不足时进入单列详情，隐藏条件区后连续尺寸回调不能反复重建筛选', async () => {
  let resize!: ResizeObserverCallback
  vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const top = this.classList.contains('job-workspace') ? document.getElementById('job-search-form') ? 700 : 60 : 0
    return { x: 0, y: top, top, bottom: top, left: 0, right: 0, width: 0, height: 0, toJSON() {} }
  })
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot()) })
  await render(); await search(); expect(body.find('.job-workspace.dual').exists()).toBe(false)
  await body.get('[data-job-select]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query.job).toBe('BOSS:same'); expect(body.find('#job-search-form').exists()).toBe(false)
  for (let index = 0; index < 4; index++) {
    resize([], {} as ResizeObserver); await flushPromises()
    expect(body.find('#job-search-form').exists()).toBe(false); expect(body.find('.job-workspace.dual').exists()).toBe(false)
    expect(button('返回列表').exists()).toBe(true)
  }
  await button('返回列表').trigger('click'); await flushPromises(); expect(body.find('#job-search-form').exists()).toBe(true)
})
it('页面滚动不改变以完整条件区高度确定的单列布局，筛选节点保持不变', async () => {
  let resize!: ResizeObserverCallback
  vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const top = this.classList.contains('job-workspace') ? 700 - window.scrollY : 0
    return { x: 0, y: top, top, bottom: top, left: 0, right: 0, width: 0, height: 0, toJSON() {} }
  })
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot()) })
  await render(); await search(); const salaryUnknown = body.get('.salary-filter input[type="checkbox"]').element
  expect(body.find('.job-workspace.dual').exists()).toBe(false)
  vi.stubGlobal('scrollY', 400); resize([], {} as ResizeObserver); await flushPromises()
  expect(body.find('.job-workspace.dual').exists()).toBe(false); expect(body.get('.salary-filter input[type="checkbox"]').element).toBe(salaryUnknown)
})
it('页签切换分别恢复选中与滚动，收藏默认首次时间倒序', async () => {
  api((url, options) => {
    if (url === '/api/job-search/favorites' && !options?.method) return respond([{ id: 'old', createdAt: '2026-10-20T00:00:00Z', sources: [job('BOSS', { title: '旧收藏' })] }, { id: 'new', createdAt: '2026-10-23T00:00:00Z', sources: [job('LIEPIN', { title: '新收藏' })] }])
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('ZHAOPIN', { title: '选择的搜索岗位', description: '不同正文' })] }))
  })
  await render(); await search(); await body.get('[data-job-select="ZHAOPIN:same"]').trigger('click'); vi.stubGlobal('scrollY', 230)
  await button('我的收藏').trigger('click'); await flushPromises(); expect(body.findAll('[data-job-select]')[0]!.text()).toContain('新收藏'); await body.get('[data-job-select="BOSS:same"]').trigger('click'); vi.stubGlobal('scrollY', 470)
  await button('搜索职位').trigger('click'); await flushPromises(); expect(body.get('[data-job-detail]').text()).toContain('选择的搜索岗位'); expect(window.scrollTo).toHaveBeenLastCalledWith({ top: 230, behavior: 'instant' })
  await button('我的收藏').trigger('click'); await flushPromises(); expect(body.get('[data-job-detail]').text()).toContain('旧收藏'); expect(window.scrollTo).toHaveBeenLastCalledWith({ top: 470, behavior: 'instant' })
})
it('切换分页期间已选岗位的正文仍能完成，不因任务操作代次改变而永久加载', async () => {
  let resolveDetail!: (value: Response) => void
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job('BOSS', { description: null })], sources: [{ source: 'BOSS', status: 'READY', count: 1, hasMore: true }] }))
    if (url === '/api/job-search/tasks/t1/jobs/BOSS%3Asame/detail') return new Promise<Response>(resolve => { resolveDetail = resolve })
    if (url === '/api/job-search/tasks/t1/more' && options?.method === 'POST') return respond(snapshot({ revision: 2, jobs: [job('BOSS', { description: null })] }))
  })
  await render(); await search(); await body.get('[aria-label="下一页"]').trigger('click'); await flushPromises(); resolveDetail(respond(job('BOSS', { description: '首项完整正文' }))); await flushPromises()
  expect(body.get('[data-job-detail]').text()).toContain('首项完整正文'); expect(body.get('[data-job-detail]').text()).not.toContain('正在读取当前来源正文')
})
it('真实路由离开停止任务且迟到响应不重新安排轮询', async () => {
  let stops = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ status: 'RUNNING', sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }] }))
    if (url === '/api/job-search/tasks/t1/stop' && options?.method === 'POST') { stops++; return respond(snapshot({ status: 'STOPPED' })) }
  })
  await render(); await search(); await router.push('/materials'); await flushPromises(); expect(stops).toBe(1)
})
it('刷新在 pagehide 用 keepalive 停止，beforeunload 取消离开不破坏当前页', async () => {
  const stops: RequestInit[] = []; let starts = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') { starts++; return respond(snapshot()) }
    if (url === '/api/job-search/tasks/t1/stop' && options?.method === 'POST') { stops.push(options); return respond(snapshot({ status: 'STOPPED' })) }
  })
  await render(); await search(); window.dispatchEvent(new Event('beforeunload', { cancelable: true })); expect(stops).toHaveLength(0)
  window.dispatchEvent(new Event('pagehide')); await flushPromises(); expect(stops).toHaveLength(1); expect(stops[0]).toMatchObject({ keepalive: true, method: 'POST', body: '{}' }); expect(body.text()).toContain('已停止，保留已取得结果')
  await body.get('#job-search-form').trigger('submit'); await flushPromises(); expect(starts).toBe(2)
})
it('月薪无效输入保持上一次有效结果，不因负数或倒置区间过滤', async () => {
  api((url, options) => { if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job(), job('LIEPIN', { salaryMinK: 8, salaryMaxK: 12, description: '独立正文' })] })) })
  await render(); await search(); await body.get('[aria-label="最低月薪"]').setValue('20'); await body.get('[aria-label="最低月薪"]').trigger('blur'); await flushPromises(); expect(body.findAll('[data-job-select]')).toHaveLength(1)
  await body.get('[aria-label="最高月薪"]').setValue('10'); await body.get('[aria-label="最高月薪"]').trigger('blur'); await flushPromises(); expect(body.text()).toContain('仍保留上一次有效筛选'); expect(body.findAll('[data-job-select]')).toHaveLength(1)
  await body.get('[aria-label="最低月薪"]').setValue('-1'); await body.get('[aria-label="最低月薪"]').trigger('blur'); await flushPromises(); expect(body.findAll('[data-job-select]')).toHaveLength(1)
})
it('分页请求迟到不能覆盖新任务，后续批次不抢走用户选择', async () => {
  let starts = 0, resolveMore!: (value: Response) => void
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(++starts === 1 ? snapshot({ sources: [{ source: 'BOSS', status: 'READY', count: 1, hasMore: true }] }) : snapshot({ id: 't2', jobs: [job('BOSS', { title: '最新任务' })] }))
    if (url === '/api/job-search/tasks/t1/more' && options?.method === 'POST') return new Promise<Response>(resolve => { resolveMore = resolve })
  })
  await render(); await search(); await body.get('[aria-label="下一页"]').trigger('click'); await body.get('#job-search-form').trigger('submit'); await flushPromises()
  resolveMore(respond(snapshot({ revision: 3, jobs: [job('BOSS', { title: '旧批次迟到' })] }))); await flushPromises(); expect(body.text()).toContain('最新任务'); expect(body.text()).not.toContain('旧批次迟到')
})
it('JD 预览期间背景搜索更新不能重写输入和冻结来源', async () => {
  vi.useFakeTimers(); let saved: unknown
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ status: 'RUNNING', sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }] }))
    if (url === '/api/job-search/tasks/t1' && !options?.method) return respond(snapshot({ revision: 2, jobs: [job('BOSS', { title: '后来标题', description: '后来正文' }), job('LIEPIN')] }))
    if (url === '/api/job-search/jd' && options?.method === 'POST') { saved = JSON.parse(String(options.body)); return respond({ materialId: 'jd1', created: true, source: { source: 'BOSS', sourceId: 'same', url: job().url, fetchedAt: job().fetchedAt, contentEdited: true } }) }
  })
  await render(); await search(); await button('保存 JD').trigger('click'); await flushPromises(); await body.get('#job-jd-content').setValue('固定编辑正文'); await vi.advanceTimersByTimeAsync(1000); await flushPromises()
  expect(body.get('[role="dialog"]').text()).toContain('测试后端岗位'); expect((body.get('#job-jd-content').element as HTMLTextAreaElement).value).toBe('固定编辑正文')
  await body.get('#job-jd-form').trigger('submit'); await flushPromises(); expect(saved).toEqual({ taskId: 't1', jobKey: 'BOSS:same', name: '合成公司－测试后端岗位', content: '固定编辑正文' })
})
it('详情加载失败有来源就近重试，返回不同来源不能写入', async () => {
  let requests = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ jobs: [job('BOSS', { description: null })] }))
    if (url === '/api/job-search/tasks/t1/jobs/BOSS%3Asame/detail' && options?.method === 'POST') return respond(++requests === 1 ? job('LIEPIN', { description: '错误来源正文' }) : job('BOSS', { description: '准确来源正文' }))
  })
  await render(); await search(); expect(body.get('[data-job-detail]').text()).toContain('岗位正文来源不一致'); expect(body.text()).not.toContain('错误来源正文')
  await button('重试正文').trigger('click'); await flushPromises(); expect(body.get('[data-job-detail]').text()).toContain('准确来源正文')
})
it('桌面可从列表明确转到详情焦点，收藏页可停止仍在进行的搜索', async () => {
  let stops = 0
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ status: 'RUNNING', sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }] }))
    if (url === '/api/job-search/tasks/t1/stop' && options?.method === 'POST') { stops++; return respond(snapshot({ status: 'STOPPED' })) }
  })
  await render(); await search(); await button('转到岗位详情').trigger('click'); expect(document.activeElement?.id).toBe('job-detail-title')
  await button('我的收藏').trigger('click'); await flushPromises(); await button('停止本次搜索').trigger('click'); await flushPromises(); expect(stops).toBe(1); expect(body.text()).not.toContain('搜索仍在进行')
})
it('收藏读取失败明确提示而不伪装零收藏，重试后读取快照', async () => {
  let reads = 0
  api((url, options) => { if (url === '/api/job-search/favorites' && !options?.method) return ++reads === 1 ? respond({ message: '本机收藏暂时不可读' }, 503) : respond([{ id: 'f1', createdAt: job().fetchedAt, sources: [job()] }]) })
  await render('/job-search?tab=favorites'); expect(body.text()).toContain('本机收藏暂时不可读'); expect(body.text()).not.toContain('还没有收藏的岗位')
  await button('重新读取收藏').trigger('click'); await flushPromises(); expect(body.text()).toContain('测试后端岗位')
})
it('删除当前收藏不删 JD、不自动换岗，焦点转到相邻条目', async () => {
  api((url, options) => {
    if (url === '/api/job-search/favorites' && !options?.method) return respond([{ id: 'f1', createdAt: '2026-10-23T00:00:00Z', sources: [job()] }, { id: 'f2', createdAt: '2026-10-22T00:00:00Z', sources: [job('LIEPIN', { title: '相邻收藏' })] }])
    if (url === '/api/job-search/favorites/f1' && options?.method === 'DELETE') return respond(null, 204)
  })
  await render('/job-search?tab=favorites'); await body.findAll('[data-cancel-favorite]')[0]!.trigger('click'); await flushPromises(); await button('确认取消收藏').trigger('click'); await flushPromises()
  expect(body.find('[data-job-detail]').exists()).toBe(false); expect(document.activeElement?.getAttribute('data-job-select')).toBe('LIEPIN:same')
})
it('迟到的新平台只追加原文筛选选项，不改变首次出现次序或列表代表来源', async () => {
  vi.useFakeTimers()
  api((url, options) => {
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(snapshot({ status: 'RUNNING', jobs: [job('LIEPIN', { experienceText: '不限' })], sources: [{ source: 'BOSS', status: 'SEARCHING', count: 0, hasMore: false }, { source: 'LIEPIN', status: 'READY', count: 1, hasMore: false }] }))
    if (url === '/api/job-search/tasks/t1' && !options?.method) return respond(snapshot({ revision: 2, jobs: [job(), job('LIEPIN', { experienceText: '不限' })] }))
  })
  await render(); await search(); expect(body.get('[data-job-select]').text()).toContain('猎聘概要')
  await vi.advanceTimersByTimeAsync(1000); await flushPromises()
  expect(body.get('#job-experience').findAll('option').map(item => item.text())).toEqual(['全部', '不限', '3–10年'])
  expect(body.get('[data-job-select]').text()).toContain('猎聘概要')
})
