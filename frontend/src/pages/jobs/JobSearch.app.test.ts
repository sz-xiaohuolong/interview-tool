import { afterEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../../App.vue'
import appRouter from '../../router'
import JobSearch from './JobSearch.vue'
let component: VueWrapper, body: DOMWrapper<HTMLElement>
let stops = 0, unknown: string[] = []
const job = { key: 'BOSS:one', source: 'BOSS', sourceId: 'one', title: '外壳回归岗位', company: '合成公司', city: '深圳', location: '深圳南山', url: '', salaryText: '20k', salaryMinK: 20, salaryMaxK: 20, salaryMonths: 12, experienceText: '不限', educationText: '本科', description: '完整正文', fetchedAt: '2026-10-23T00:00:00Z' }
const task = { id: 't1', keyword: 'Java', cityId: 'sz', cityName: '深圳', status: 'RUNNING', revision: 1, sources: [{ source: 'BOSS', status: 'SEARCHING', count: 1, hasMore: false }], jobs: [job] }
const respond = (value: unknown) => new Response(JSON.stringify(value))
async function render(width: number, handler?: (url: string, options?: RequestInit) => Response | undefined) {
  vi.stubGlobal('innerWidth', width); vi.stubGlobal('innerHeight', 1000); vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    const response = handler?.(url, options); if (response) return response
    if (url === '/api/health' && !options?.method) return respond({ status: 'ok', provider: 'mock', configured: true, model: '明确测试替身' })
    if (url === '/api/job-search/cities' && !options?.method) return respond([{ id: 'sz', name: '深圳' }])
    if (url === '/api/job-search/favorites' && !options?.method) return respond([])
    if (url === '/api/job-search/tasks' && options?.method === 'POST') return respond(task)
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return respond({ 'BOSS:one': null })
    if (url === '/api/job-search/tasks/t1/stop' && options?.method === 'POST') { stops++; return respond({ ...task, status: 'STOPPED' }) }
    unknown.push(`${url} ${options?.method}`); throw new Error(`未知请求：${url}`)
  }))
  const record = appRouter.getRoutes().find(item => item.path === '/job-search')!
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: record.path, meta: record.meta, component: JobSearch }, { path: '/:pathMatch(.*)*', component: { template: '<section>其他页面</section>' } }] })
  await router.push('/job-search'); await router.isReady()
  component = mount(App, { attachTo: document.body, global: { plugins: [router], stubs: { DailyQuote: true, ModelSettingsDialog: true } } }); body = new DOMWrapper(document.body); await flushPromises()
  await body.get('#job-keyword').setValue('Java'); await body.get('#job-city').setValue('深圳'); await body.get('#job-search-form').trigger('submit'); await flushPromises()
  return router
}
function button(text: string) { return body.findAll('button').find(item => item.text().trim() === text)! }
afterEach(() => { component?.unmount(); document.body.innerHTML = ''; localStorage.clear(); vi.unstubAllGlobals(); expect(unknown).toEqual([]); unknown = []; stops = 0 })
it('真实 App 外壳内页签改变 query 时保留任务、结果与选择，不触发停止', async () => {
  await render(1440); await button('我的收藏').trigger('click'); await flushPromises(); expect(stops).toBe(0); expect(body.text()).toContain('搜索仍在进行')
  await button('搜索职位').trigger('click'); await flushPromises(); expect(body.find('[data-job-select="BOSS:one"]').exists()).toBe(true); expect(body.get('[data-job-detail]').text()).toContain('完整正文'); expect(stops).toBe(0)
})
it('真实 App 外壳内窄屏进入详情、系统返回均保留任务，离开模块才停止', async () => {
  const router = await render(768); await body.get('[data-job-select="BOSS:one"]').trigger('click'); await flushPromises(); expect(body.get('[data-job-detail]').text()).toContain('完整正文'); expect(stops).toBe(0)
  await button('返回列表').trigger('click'); await flushPromises(); expect(body.find('[data-job-select="BOSS:one"]').exists()).toBe(true); expect(stops).toBe(0)
  await router.push('/materials'); await flushPromises(); expect(stops).toBe(1)
})
it('真实 App 保存成功提示使双栏降为单列时保留详情及按钮身份，仍可打开准确 JD', async () => {
  let resize!: ResizeObserverCallback, saved = false
  vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const top = this.classList.contains('job-workspace') ? !document.getElementById('job-search-form') ? 60 : document.body.textContent?.includes('已保存到目标 JD') ? 700 : 612 : 0
    return { x: 0, y: top, top, bottom: top, left: 0, right: 0, width: 0, height: 0, toJSON() {} }
  })
  const link = { materialId: 'jd-exact', source: 'BOSS', sourceId: 'one', url: '', fetchedAt: job.fetchedAt, contentEdited: true }
  const router = await render(1440, (url, options) => {
    if (url === '/api/job-search/jd' && options?.method === 'POST') { saved = true; return respond({ materialId: link.materialId, created: true, source: link }) }
    if (url === '/api/job-search/jd-links/lookup' && options?.method === 'POST') return respond({ 'BOSS:one': saved ? link : null })
  })
  expect(body.find('.job-workspace.dual').exists()).toBe(true); await body.get('[data-job-select]').trigger('click'); await flushPromises()
  await button('保存 JD').trigger('click'); await flushPromises(); await body.get('#job-jd-content').setValue('编辑后的合成正文')
  await body.get('#job-jd-form').trigger('submit'); await flushPromises(); expect(saved).toBe(true)
  const detail = body.get('[data-job-detail]').element, savedButton = button('查看已保存 JD').element
  resize([], {} as ResizeObserver); await flushPromises()
  expect(body.find('.job-workspace.dual').exists()).toBe(false)
  expect(body.get('[data-job-detail]').element).toBe(detail); expect(button('查看已保存 JD').element).toBe(savedButton)
  expect(router.currentRoute.value.query.job).toBe('BOSS:one'); expect(stops).toBe(0)
  await button('查看已保存 JD').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/materials?kind=JD&materialId=jd-exact'); expect(stops).toBe(1)
})
it('桌面默认预览尚未主动打开时，降低高度显示单列列表而不自动进入详情', async () => {
  let resize!: ResizeObserverCallback
  vi.stubGlobal('ResizeObserver', class { constructor(callback: ResizeObserverCallback) { resize = callback } observe() {} disconnect() {} })
  const router = await render(1440); expect(body.find('[data-job-detail]').exists()).toBe(true)
  vi.stubGlobal('innerHeight', 300); resize([], {} as ResizeObserver); await flushPromises()
  expect(body.find('.job-workspace.dual').exists()).toBe(false); expect(router.currentRoute.value.query.job).toBeUndefined()
  expect(body.find('[data-job-detail]').exists()).toBe(false); expect(body.find('#job-search-form').exists()).toBe(true); expect(stops).toBe(0)
})
