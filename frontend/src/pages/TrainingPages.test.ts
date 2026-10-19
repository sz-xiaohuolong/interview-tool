import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import JdPage from './JdDiagnostics.vue'
import StoryPage from './Stories.vue'
import DesignPage from './DesignPractice.vue'
import PracticePage from './Practice.vue'
async function show(component: any, path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/practice/:id', component }, { path: '/sprints/:id', component }, { path: '/:pathMatch(.*)*', component }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(component, { global: { plugins: [router] } }); await flushPromises(); return wrapper
}
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('清单只允许同优先级相邻移动，移动后显示完整的新顺序', async () => {
  const actions = [1, 2, 2, 3].map((priority, i) => ({ id: ['a', 'b', 'c', 'd'][i], priority, status: 'TODO', purpose: ['容量缺口', '事务未验证', '幂等未验证', '其他要求'][i], type: 'VERIFY_REQUIREMENT', requirementSnapshot: {}, sourceDiagnosticId: 'diagnostic' }))
  let sprint = { id: 's', revision: 0, status: 'ACTIVE', diagnosticId: 'diagnostic', actions }
  const requests: any[] = []
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (url === '/api/training/sprints/s/actions' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)); requests.push(body)
      sprint = { ...sprint, revision: sprint.revision + 1, actions: body.actionIds.map((id: string) => actions.find(a => a.id === id)!) }
    }
    return new Response(JSON.stringify(sprint))
  })
  const wrapper = await show(JdPage, '/sprints/s')
  const rows = wrapper.findAll('article.training-finding')
  expect(rows).toHaveLength(4)
  expect(rows.map(row => row.findAll('button').filter(b => ['上移', '下移'].includes(b.text())).map(b => b.attributes('disabled') !== undefined))).toEqual([[true, true], [true, false], [false, true], [true, true]])
  await rows[1]!.findAll('button').find(b => b.text() === '下移')!.trigger('click'); await flushPromises()
  expect(requests).toHaveLength(1); expect(requests[0].actionIds).toEqual(['a', 'c', 'b', 'd'])
  expect(wrapper.findAll('article.training-finding h3').map(h => h.text())).toEqual(['容量缺口', '幂等未验证', '事务未验证', '其他要求'])
  wrapper.unmount()
})
it('JD 空输入不能进入模型调用，预览阶段与生成阶段分开', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', async (url: string) => { calls.push(url); return new Response(JSON.stringify({ items: [], nextCursor: null })) })
  const wrapper = await show(JdPage, '/jd-diagnostics')
  expect(wrapper.get('[data-action="prepare-jd"]').attributes('disabled')).toBeDefined()
  expect(calls.filter(x => x === '/api/training/jd-diagnostics')).toHaveLength(0)
  expect(wrapper.text()).toContain('先预览发送范围')
})
it('故事保存前清楚说明个人贡献与待补事实，不自动补造成果', async () => {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ items: [], nextCursor: null })))
  const wrapper = await show(StoryPage, '/stories')
  expect(wrapper.text()).toContain('个人行动')
  expect(wrapper.text()).toContain('待补充')
  expect(wrapper.findAll('textarea').every(t => !(t.element as HTMLTextAreaElement).value)).toBe(true)
})
it('设计反馈就绪后仍需用户明确确认，不由模型自动推进', async () => {
  const session = { id: 'd', revision: 2, state: 'IN_PROGRESS', currentStage: 'CLARIFY', mock: true, scenario: { title: '订单服务设计', description: '合成场景' }, stages: [{ id: 'CLARIFY', title: '需求澄清', state: 'FEEDBACK_READY', currentArtifactVersion: 1, draft: { goals: '澄清目标' }, fieldDefinitions: [{ id: 'goals', label: '目标' }], artifactVersions: [], currentArtifact: { version: 1, feedbackStatus: 'READY', fields: { goals: '澄清目标' }, feedback: { strengths: [], gaps: ['容量未验证'], uncertain: [], tradeoffs: [], openIssues: [], evidenceRefs: [] } } }] }
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify(session)))
  const wrapper = await show(DesignPage, '/design-practice?id=d')
  expect(wrapper.get('[data-action="advance-stage"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[data-confirm-open]').setValue(true)
  expect(wrapper.get('[data-action="advance-stage"]').attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).toContain('容量未验证')
})
it('同题页查询成功操作后刷新当前回答的评估详情', async () => {
  let ready = false
  const attempt = () => ({ id: 'a', ordinal: 1, revision: ready ? 3 : 2, status: ready ? 'READY' : 'FAILED', answerText: '已封存原文', effectiveExposure: 'UNKNOWN', evaluation: { id: 'g', status: ready ? 'READY' : 'FAILED', operationId: 'op', result: { checks: [], suggestions: ready ? ['恢复后可见建议'] : [] } } })
  vi.stubGlobal('fetch', async (url: string) => {
    let response: unknown = { items: [], nextCursor: null }
    if (url === '/api/training/practice-cases/c') response = { id: 'c', sourceKind: 'STORY', question: '合成问题', priorDialogue: [], context: '', caseVersion: 1, analysisExposure: 'UNKNOWN' }
    if (url.includes('/attempts?') || url.endsWith('/recent-attempts')) response = { items: [attempt()], nextCursor: null }
    if (url === '/api/training/practice-attempts/a') response = attempt()
    if (url === '/api/training/operations/op') { ready = true; response = { id: 'op', state: 'SUCCEEDED', generation: 1 } }
    return new Response(JSON.stringify(response))
  })
  const wrapper = await show(PracticePage, '/practice/c')
  await wrapper.findAll('button').find(b => b.text() === '查看本次操作')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('恢复后可见建议')
})
