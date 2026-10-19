import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import appRouter from '../router'
import App from '../App.vue'
import { registerInterviewRoutes } from '../routing/interviews'

beforeEach(() => vi.stubGlobal('scrollTo', vi.fn()))
let wrapper: VueWrapper | undefined
const respond = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
async function show(path = '/configure/quick') {
  const router = createRouter({ history: createMemoryHistory(), routes: appRouter.options.routes })
  registerInterviewRoutes(router)
  await router.push(path); await router.isReady()
  wrapper = mount(RouterView, { global: { plugins: [router] } }); await flushPromises()
  return router
}
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })
function endpoint(createStatus = 200) {
  const writes: Record<string, unknown>[] = []
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/interviews/status') return respond({ enabled: true, provider: 'openai', configured: true })
    if (url === '/api/interviews' && options?.method === 'POST') { writes.push(JSON.parse(String(options.body))); return respond(createStatus === 200 ? { id: 'quick-created' } : { message: '快速练习创建失败' }, createStatus) }
    if (url === '/api/interviews/quick-created') return respond({ id: 'quick-created', sessionType: 'QUICK', status: 'IN_PROGRESS', revision: 1, role: 'Java 后端工程师', level: 'JUNIOR', phase: '', plannedQuestionCount: 5, mock: false, createdAt: '2026-10-19T00:00:00Z', messages: [{ id: 'first', role: 'ASSISTANT', questionId: 'q1', content: '如何处理并发请求？', createdAt: '2026-10-19T00:00:00Z' }], sources: [], warnings: [], allowedActions: ['answers', 'finish'] })
    if (url === '/api/materials' || url === '/api/interviews') return respond([])
    throw new Error(`出现未约定接口：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  return { writes, fetcher }
}
it('快速入口只选岗位题量，默认五道主问题且不读取资料库', async () => {
  const { fetcher } = endpoint(); const router = await show()
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  expect(wrapper!.get('h1').text()).toContain('快速练习')
  expect(wrapper!.get<HTMLSelectElement>('#quick-question-count').element.value).toBe('5')
  expect(wrapper!.get('#quick-question-count').findAll('option').map(item => item.element.value)).toEqual(['3', '5', '8'])
  expect(wrapper!.find('#quick-level').element.closest('details')?.hasAttribute('open')).toBe(false)
  expect(wrapper!.find('textarea, input[type=file]').exists()).toBe(false)
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual(['/api/interviews/status'])
})
it('自定义岗位与折叠职级提交到同一会话接口，准备后直接作答', async () => {
  const { writes, fetcher } = endpoint(); const router = await show()
  await wrapper!.get('#quick-role').setValue('CUSTOM')
  await wrapper!.get('#quick-custom-role').setValue('数据平台工程师')
  await wrapper!.get('#quick-question-count').setValue('3')
  await wrapper!.get('#quick-level').setValue('SENIOR')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ sessionType: 'QUICK', role: '数据平台工程师', level: 'SENIOR', plannedQuestionCount: 3, resumeId: '', jdId: '', jd: '' })
  expect(writes[0]?.requestId).toEqual(expect.any(String))
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/interviews/quick-created'))
  await flushPromises()
  expect(wrapper!.text()).toContain('如何处理并发请求？')
  expect(wrapper!.find('[data-action=confirm]').exists()).toBe(false)
  expect(fetcher.mock.calls.some(([url]) => url.startsWith('/api/sessions'))).toBe(false)
})
it('创建失败保留配置并明确报错，不能改调旧接口', async () => {
  const { writes, fetcher } = endpoint(503); const router = await show()
  await wrapper!.get('#quick-role').setValue('Java 后端工程师')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(wrapper!.get('[role=alert]').text()).toContain('快速练习创建失败')
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  expect(writes).toHaveLength(1)
  expect(fetcher.mock.calls.some(([url]) => url.startsWith('/api/sessions'))).toBe(false)
})

 it('真实应用中的关联训练仍提交旧场次接口并保留训练目标', async () => {
  const writes: { url: string; body: Record<string, unknown> }[] = []
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/health') return respond({ provider: 'openai', configured: true })
    if (url === '/api/sessions' && options?.method === 'POST') { writes.push({ url, body: JSON.parse(String(options.body)) }); return respond({ id: 'linked-created' }) }
    throw new Error(`出现未约定接口：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: appRouter.options.routes })
  registerInterviewRoutes(router)
  router.addRoute({ path: '/sessions/:id', component: { template: '<div>已进入关联面试</div>' } })
  await router.push('/configure/quick?trainingLinkId=linked-source&focus=缓存击穿&role=Java&level=SENIOR'); await router.isReady()
  wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.find('#quick-role').exists()).toBe(false)
  expect(wrapper.get<HTMLTextAreaElement>('#linked-focus').element.value).toBe('缓存击穿')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/sessions/linked-created'))
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ url: '/api/sessions', body: { mode: 'QUICK', trainingLinkId: 'linked-source', focus: '缓存击穿', role: 'Java', level: 'SENIOR' } })
  expect(fetcher.mock.calls.some(([url]) => url.startsWith('/api/interviews'))).toBe(false)
})
