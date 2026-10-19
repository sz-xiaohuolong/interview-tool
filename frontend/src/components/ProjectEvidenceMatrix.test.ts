import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Report from '../pages/FocusedInterviewReport.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; sessionStorage.clear(); vi.unstubAllGlobals() })
const checkpoints = [
  ['BUSINESS', '业务背景与指标'], ['RESPONSIBILITY', '个人真实职责'], ['ARCHITECTURE', '架构与调用链路'],
  ['TECH_CHOICE', '关键技术选型依据'], ['FAILURE', '异常与故障处理'], ['PERFORMANCE', '性能/压力证据'], ['TRADEOFF', '妥协与替代方案'],
]
const matrix = { version: 1, project: { id: 'p1', name: '合成订单服务' }, notice: '仅反映本场已提交文字；未覆盖不代表能力不足。', items: checkpoints.map(([checkpointId, title]) => ({
  checkpointId, title, status: checkpointId === 'RESPONSIBILITY' ? 'PARTIAL' : checkpointId === 'ARCHITECTURE' ? 'CLEAR' : 'UNCOVERED',
  reason: checkpointId === 'RESPONSIBILITY' ? '已有接口职责，尚缺实施边界。' : '本场覆盖情况。',
  evidence: checkpointId === 'RESPONSIBILITY' ? [{ questionMessageId: 'q1', answerMessageId: 'a1', questionQuote: '你负责什么', answerQuote: '我负责订单接口' }] : [],
})) }
function session() { return { id: 's1', mode: 'PROJECT', role: 'Java 后端', level: 'JUNIOR', status: 'COMPLETED', createdAt: '2026-10-18T00:00:00Z', reportStatus: 'READY', mock: false, messages: [] } }
function report(withMatrix = true) { return { sessionId: 's1', summary: '合成复盘', dimensions: [], questions: [], weaknesses: [], suggestions: [], ...(withMatrix ? { matrix } : {}) } }
async function showPage(withMatrix = true, gaps: any[] = []) {
  const writes: { path: string; body: any }[] = [], reads: string[] = []
  vi.stubGlobal('fetch', async (path: string, options?: RequestInit) => {
    if (options?.method === 'POST') { writes.push({ path, body: JSON.parse(String(options.body)) }); return new Response(JSON.stringify({ caseId: 'case-new', checkpointId: 'PERFORMANCE', status: 'READY' })) }
    reads.push(path)
    if (path === '/api/sessions/s1') return new Response(JSON.stringify(session()))
    if (path === '/api/sessions/s1/report') return new Response(JSON.stringify(report(withMatrix)))
    if (path === '/api/project-evidence/SESSION/s1/gaps') return new Response(JSON.stringify({ items: gaps }))
    throw new Error(`意外请求：${path}`)
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/reports/:id', component: Report }, { path: '/practice/:id', component: { template: '<div>单题练习</div>' } }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/reports/s1'); await router.isReady()
  const wrapper = mount(Report, { global: { plugins: [router] } }); wrappers.push(wrapper); await flushPromises()
  return { wrapper, router, writes, reads }
}

it('新项目报告显示七项经历证据和真实回答，未覆盖不等于能力不足', async () => {
  const { wrapper, writes } = await showPage()
  for (const [, title] of checkpoints) expect(wrapper.text()).toContain(title)
  expect(wrapper.text()).toContain('已讲清'); expect(wrapper.text()).toContain('待补强'); expect(wrapper.text()).toContain('未覆盖')
  expect(wrapper.text()).toContain('我负责订单接口')
  expect(wrapper.text()).toContain('未覆盖不代表能力不足')
  expect(writes).toHaveLength(0)
})

it('旧报告没有矩阵时不补算也不读取补练接口', async () => {
  const { wrapper, writes, reads } = await showPage(false)
  expect(wrapper.text()).not.toContain('项目证据覆盖')
  expect(reads).not.toContain('/api/project-evidence/SESSION/s1/gaps')
  expect(writes).toHaveLength(0)
})

it('缺口一次点击生成项目定向新题并进入原单题页面', async () => {
  const { wrapper, router, writes } = await showPage()
  await wrapper.get('[data-gap-create="PERFORMANCE"]').trigger('click'); await flushPromises()
  expect(writes).toHaveLength(1)
  expect(writes[0].path).toBe('/api/project-evidence/SESSION/s1/gaps/PERFORMANCE')
  expect(writes[0].body.requestId).toBeTruthy()
  expect(router.currentRoute.value.path).toBe('/practice/case-new')
})

it('已有补练与结果只导航恢复，已讲清项没有缺口生成按钮', async () => {
  const { wrapper, writes } = await showPage(true, [{ checkpointId: 'PERFORMANCE', caseId: 'saved-case', status: 'READY', latestAttempt: { id: 'answer-one', status: 'READY' } }])
  expect(wrapper.find('[data-gap-create="ARCHITECTURE"]').exists()).toBe(false)
  const saved = wrapper.get('[data-gap-continue="PERFORMANCE"]')
  expect(saved.attributes('href')).toBe('/practice/saved-case')
  expect(wrapper.get('[data-gap-result="PERFORMANCE"]').attributes('href')).toBe('/practice/saved-case?attemptId=answer-one')
  expect(writes).toHaveLength(0)
})
