import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import appRouter from '../../router'
import App from '../../App.vue'
import { registerInterviewRoutes } from '../../routing/interviews'

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
function endpoint(createStatus = 200, practiceOptions: Record<string, unknown> = {}, optionsStatus = 200) {
  const writes: Record<string, unknown>[] = []
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/interviews/status') return respond({ enabled: true, provider: 'openai', configured: true })
    if (url === '/api/interviews/agent-practice-options' && options?.method === 'POST') return respond(practiceOptions, optionsStatus)
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

it('Agent 默认综合抽查直接创建，不增加复习预检请求', async () => {
  const { writes, fetcher } = endpoint(); await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  expect(wrapper!.get('[data-agent-topics]').element.closest('details')?.hasAttribute('open')).toBe(false)
  expect(wrapper!.get('[data-agent-topics]').text()).toContain('自动综合抽查')
  expect(wrapper!.findAll('[name="agentTopics"]')).toHaveLength(6)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]).toMatchObject({ agentTopics: [], practicePreference: 'NEW_FIRST' })
  expect(writes[0]).not.toHaveProperty('acceptReviewFill')
  expect(fetcher.mock.calls.map(([url]) => url).filter(url => url === '/api/interviews/agent-practice-options')).toEqual([])
})

it.each([
  [['AGENT_CONTEXT_MEMORY']],
  [['AGENT_ARCHITECTURE', 'AGENT_HARNESS_TOOL', 'AGENT_RAG']],
])('Agent 主题选择按所选稳定标识提交：%j', async selected => {
  const { writes } = endpoint(); await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  for (const value of selected) await wrapper!.get(`[name="agentTopics"][value="${value}"]`).setValue(true)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]?.agentTopics).toEqual(selected)
})

it.each(['平台 aGeNt 工程师', '大模型平台工程师', '人工智能应用工程师', '智能体工程师', 'LLM 开发工程师', 'RAG 开发工程师', 'AI 应用工程师'])('自定义 Agent 岗位支持主题，切回普通岗位不发送相关配置：%s', async name => {
  const { writes } = endpoint(); await show()
  await wrapper!.get('#quick-role').setValue('CUSTOM')
  await wrapper!.get('#quick-custom-role').setValue(name)
  expect(wrapper!.find('[data-agent-topics]').exists()).toBe(true)
  await wrapper!.get('[name="agentTopics"][value="AGENT_FRAMEWORK"]').setValue(true)
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('#quick-role').setValue('Java 后端工程师')
  expect(wrapper!.find('[data-agent-topics]').exists()).toBe(false)
  expect(wrapper!.find('#quick-practice-preference').exists()).toBe(false)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]).not.toHaveProperty('agentTopics')
  expect(writes[0]).not.toHaveProperty('practicePreference')
  expect(writes[0]).not.toHaveProperty('acceptReviewFill')
})

it('普通 storage 岗位不因包含 rag 字母片段展示 Agent 配置', async () => {
  const { writes } = endpoint(); await show()
  await wrapper!.get('#quick-role').setValue('CUSTOM')
  await wrapper!.get('#quick-custom-role').setValue('Storage Engineer')
  expect(wrapper!.find('[data-agent-topics]').exists()).toBe(false)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]).not.toHaveProperty('agentTopics')
  expect(writes[0]).not.toHaveProperty('practicePreference')
})

it('复习空池只显示切换未练优先操作，明确切换后才创建', async () => {
  const { writes, fetcher } = endpoint(200, { eligible: true, requestedCount: 5, reviewAvailable: 0, newSupplementCount: 5, canStart: false, requiresAcknowledgement: false, message: '当前没有可复习的已练考点。' })
  await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(0)
  expect(wrapper!.text()).toContain('当前没有可复习的已练考点')
  const preflight = fetcher.mock.calls.find(([url]) => url === '/api/interviews/agent-practice-options')!
  expect(preflight[1]?.method).toBe('POST')
  expect(JSON.parse(String(preflight[1]?.body))).toEqual({ role: '大模型应用 / Agent 开发工程师', level: 'JUNIOR', plannedQuestionCount: 5, agentTopics: [] })
  await wrapper!.get('[data-switch-new-first]').trigger('click')
  expect(wrapper!.get<HTMLSelectElement>('#quick-practice-preference').element.value).toBe('NEW_FIRST')
  expect(writes).toHaveLength(0)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes[0]?.practicePreference).toBe('NEW_FIRST')
  expect(fetcher.mock.calls.filter(([url]) => url === '/api/interviews/agent-practice-options')).toHaveLength(1)
})

it('复习不足必须展示补新数量，第二次明确开始才携带补新确认创建', async () => {
  const { writes, fetcher } = endpoint(200, { eligible: true, requestedCount: 5, reviewAvailable: 3, newSupplementCount: 2, canStart: true, requiresAcknowledgement: true, message: '复习考点不足。' })
  const router = await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  expect(writes).toHaveLength(0)
  expect(wrapper!.get('[data-review-options]').text()).toContain('2 道新考点')
  expect(wrapper!.get('button[type="submit"]').text()).toContain('确认')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ practicePreference: 'REVIEW', acceptReviewFill: true, expectedNewSupplementCount: 2 })
  expect(fetcher.mock.calls.filter(([url]) => url === '/api/interviews/agent-practice-options')).toHaveLength(1)
})

it('复习确认创建被拒绝后清除确认，下次开始重新预检', async () => {
  const { writes, fetcher } = endpoint(409, { eligible: true, requestedCount: 5, reviewAvailable: 3, newSupplementCount: 2, canStart: true, requiresAcknowledgement: true, message: '需要补充新考点。' })
  await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]?.expectedNewSupplementCount).toBe(2)
  expect(wrapper!.get('[role="alert"]').text()).toContain('快速练习创建失败')
  expect(wrapper!.find('[data-review-options]').exists()).toBe(false)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(fetcher.mock.calls.filter(([url]) => url === '/api/interviews/agent-practice-options')).toHaveLength(2)
  expect(wrapper!.find('[data-review-options]').exists()).toBe(true)
})

it('复习预检失败明确显示错误并保留配置，不创建或转旧接口', async () => {
  const { writes, fetcher } = endpoint(200, { message: '复习记录暂时无法读取。' }, 503)
  const router = await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(wrapper!.get('[role="alert"]').text()).toContain('复习记录暂时无法读取')
  expect(wrapper!.get<HTMLSelectElement>('#quick-practice-preference').element.value).toBe('REVIEW')
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  expect(writes).toHaveLength(0)
  expect(fetcher.mock.calls.some(([url]) => url.startsWith('/api/sessions'))).toBe(false)
})

it.each(['count', 'level', 'topics', 'role', 'preference'])('修改配置撤销复习补新确认：%s', async field => {
  const { writes, fetcher } = endpoint(200, { eligible: true, requestedCount: 5, reviewAvailable: 2, newSupplementCount: 3, canStart: true, requiresAcknowledgement: true, message: '需要确认补新。' })
  await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  if (field === 'count') await wrapper!.get('#quick-question-count').setValue('3')
  if (field === 'level') await wrapper!.get('#quick-level').setValue('SENIOR')
  if (field === 'topics') await wrapper!.get('[name="agentTopics"][value="AGENT_EVALUATION"]').setValue(true)
  if (field === 'role') {
    await wrapper!.get('#quick-role').setValue('CUSTOM')
    await wrapper!.get('#quick-custom-role').setValue('Agent 应用工程师')
  }
  if (field === 'preference') {
    await wrapper!.get('#quick-practice-preference').setValue('NEW_FIRST')
    await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  }
  expect(wrapper!.find('[data-review-options]').exists()).toBe(false)
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(0)
  expect(fetcher.mock.calls.filter(([url]) => url === '/api/interviews/agent-practice-options')).toHaveLength(2)
})

it('复习池充足时同一次开始完成预检与创建，不发送补新确认', async () => {
  const { writes, fetcher } = endpoint(200, { eligible: true, requestedCount: 5, reviewAvailable: 8, newSupplementCount: 0, canStart: true, requiresAcknowledgement: false, message: '' })
  await show()
  await wrapper!.get('#quick-role').setValue('大模型应用 / Agent 开发工程师')
  await wrapper!.get('[name="agentTopics"][value="AGENT_RAG"]').setValue(true)
  await wrapper!.get('#quick-practice-preference').setValue('REVIEW')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ agentTopics: ['AGENT_RAG'], practicePreference: 'REVIEW' })
  expect(writes[0]).not.toHaveProperty('acceptReviewFill')
  expect(fetcher.mock.calls.map(([url, options]) => [url, options?.method]).filter(([, method]) => method === 'POST')).toEqual([['/api/interviews/agent-practice-options', 'POST'], ['/api/interviews', 'POST']])
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
