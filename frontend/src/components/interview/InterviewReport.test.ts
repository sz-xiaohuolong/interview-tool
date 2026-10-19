import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ReportReview from './InterviewReport.vue'

let wrapper: ReturnType<typeof mount>
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })
const fixture = () => ({ id: 'report-test', status: 'REPORT_READY', role: 'Java 后端', level: 'JUNIOR', mock: true, messages: [
  { id: 'm1', role: 'ASSISTANT', questionId: 'q1', rootQuestionId: 'root', questionKind: 'MAIN', content: '如何处理超时？' },
  { id: 'm2', role: 'USER', questionId: 'q1', rootQuestionId: 'root', questionKind: 'MAIN', content: '数据库超时后返回错误。' },
  { id: 'm3', role: 'ASSISTANT', questionId: 'q2', rootQuestionId: 'root', questionKind: 'FOLLOW_UP', content: '如何避免重复执行？' },
  { id: 'm4', role: 'USER', questionId: 'q2', rootQuestionId: 'root', questionKind: 'FOLLOW_UP', content: '使用唯一键避免重复写入。' },
  { id: 'm5', role: 'ASSISTANT', questionId: 'q3', rootQuestionId: 'next', questionKind: 'MAIN', content: '尚未回答的问题' },
], sources: [], report: { version: 2, summary: '本场回答保存完整。', overview: { answeredCount: 2, mainQuestionCount: 1, followUpCount: 1, endReason: 'USER_FINISHED' }, stages: [{ id: 'INTRO', label: '开场介绍', status: 'COMPLETED', answeredCount: 2 }, { id: 'QUESTIONS', label: '候选人反问', status: 'NOT_STARTED', answeredCount: 0 }], findings: [
  { questionId: 'q1', headline: '补充超时边界', answerStatus: 'PARTIAL', status: 'INSUFFICIENT', answerQuote: '数据库超时后返回错误', observation: '缺少重试边界', assessment: '官方依据待核实', suggestion: '说明重试触发条件', sourceIds: [], priority: 1, terms: [{ term: 'Trade-off', explanation: '收益与代价之间的取舍' }] },
  { questionId: 'q2', headline: '幂等约束清楚', answerStatus: 'CLEAR', status: 'INSUFFICIENT', answerQuote: '唯一键避免重复写入', observation: '给出了具体防重复措施', assessment: '官方依据待核实', suggestion: '继续解释唯一键冲突处理', sourceIds: [], priority: 2, terms: [] },
] } })
async function show(value: any = fixture()) {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ items: [] })))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/interviews/report-test'); await router.isReady()
  wrapper = mount(ReportReview, { props: { session: value }, global: { plugins: [router] } }); await flushPromises()
  return router
}
it('首屏区分内容与依据，按主问题折叠并能定位原答', async () => {
  await show()
  expect(wrapper.get('[data-overview]').text()).toContain('2 次作答')
  expect(wrapper.get('[data-strengths]').text()).toContain('幂等约束清楚')
  expect(wrapper.get('[data-improvements]').text()).toContain('补充超时边界')
  expect(wrapper.findAll('[data-question-group]')).toHaveLength(2)
  const group = wrapper.get('[data-question-group]')
  expect(group.attributes('open')).toBeUndefined()
  await wrapper.get('[data-locate="q1"]').trigger('click')
  expect(group.attributes('open')).toBeDefined()
  expect(wrapper.get('[data-answer="q1"]').text()).toContain('数据库超时后返回错误')
  expect(wrapper.text()).toContain('收益与代价之间的取舍')
  expect(wrapper.text()).toContain('未作答，本题尚未验证')
})
it('没有充分样本不强凑优势，不把尚未验证列成短板', async () => {
  const value = fixture(); value.report.findings.forEach(f => { f.answerStatus = 'UNVERIFIED' })
  await show(value)
  expect(wrapper.findAll('[data-strength-item]')).toHaveLength(0)
  expect(wrapper.findAll('[data-improvement-item]')).toHaveLength(0)
  expect(wrapper.text()).toContain('尚无足够证据提炼优势')
})
it('定向补练默认折叠，展开或收起不创建练习', async () => {
  await show()
  const requests: string[] = []
  vi.stubGlobal('fetch', async (url: string) => {
    requests.push(url)
    throw new Error(`不应发起请求：${url}`)
  })
  const training = wrapper.findAll('details').find(item => item.find('summary').text() === '定向补练')
  expect(training, '补练入口应集中在原生折叠区域').toBeDefined()
  expect(training!.attributes('open')).toBeUndefined()
  expect(wrapper.get('[data-improvements]').findAll('button').map(button => button.text())).toEqual(['定位原回答'])
  ;(training!.element as HTMLDetailsElement).open = true
  await training!.trigger('toggle')
  expect(training!.get('[data-review-gap-create="q1"]').text()).toBe('定向补练一道新题')
  ;(training!.element as HTMLDetailsElement).open = false
  await training!.trigger('toggle')
  await flushPromises()
  expect(requests).toEqual([])
})
it('定向补练导航新案例，刷新复用并可回看结果', async () => {
  const router = await show()
  vi.stubGlobal('fetch', async (_url: string, options?: RequestInit) => new Response(JSON.stringify(options?.method === 'POST' ? { caseId: 'new-case', questionId: 'q1', status: 'READY' } : { items: [{ caseId: 'new-case', questionId: 'q1', status: 'READY', latestAttempt: { id: 'attempt', status: 'READY' } }] })))
  await wrapper.get('[data-review-gap-create="q1"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/new-case')
  expect(wrapper.get('[data-review-gap-continue="q1"]').attributes('href')).toContain('/practice/new-case')
})
it('离开报告后生成成功只保留服务端结果，不强制跳转', async () => {
  const router = await show()
  let complete!: (response: Response) => void
  vi.stubGlobal('fetch', async (_url: string, options?: RequestInit) => options?.method === 'POST' ? new Promise<Response>(resolve => { complete = resolve }) : new Response(JSON.stringify({ items: [] })))
  await wrapper.get('[data-review-gap-create="q1"]').trigger('click')
  wrapper.unmount(); await router.push('/materials')
  complete(new Response(JSON.stringify({ caseId: 'late-case', questionId: 'q1', status: 'READY' }))); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/materials')
})
it('切换失败补练后不沿用上一题的重试按钮', async () => {
  const value = fixture(); value.report.findings[1].answerStatus = 'PARTIAL'
  await show(value)
  const writes: string[] = []
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (options?.method === 'POST') { writes.push(url); return new Response(JSON.stringify({ caseId: url.endsWith('q1') ? 'c1' : 'c2', questionId: url.endsWith('q1') ? 'q1' : 'q2', status: 'FAILED', operationId: url.endsWith('q1') ? 'op1' : 'op2' })) }
    if (url.includes('/operations/')) return new Response(JSON.stringify({ id: url.split('/').at(-1), state: 'FAILED', generation: 0 }))
    return new Response(JSON.stringify({ items: [] }))
  })
  await wrapper.get('[data-review-gap-create="q1"]').trigger('click'); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '查看本次操作')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('确认重试未完成的评估')
  await wrapper.get('[data-review-gap-create="q2"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).not.toContain('确认重试未完成的评估')
  expect(writes).toHaveLength(2)
})

it('详细评估展示四部分正文与行动，并能定位关联原答', async () => {
  const value: any = fixture()
  value.report.detailedAssessment = { version: 1,
    overall: { text: '已给出防重复措施，超时恢复仍需补充。', questionIds: ['q1', 'q2'] },
    strengths: [{ text: '用唯一键约束重复写入，让幂等措施落到了数据库层。', questionIds: ['q2'] }],
    improvements: [{ text: '未说明超时后哪些操作允许重试，无法判断恢复边界。', questionIds: ['q1'] }],
    nextSteps: [{ text: '绘制恢复状态图，并用重复请求测试确认只写入一次。', questionIds: ['q1'] }] }
  await show(value)
  const detail=wrapper.get('[data-detailed-assessment]')
  expect(detail.findAll('h3').map(item => item.text())).toEqual(['整体印象', '亮点分析', '可提升空间', '下一步行动'])
  expect(detail.get('ol').text()).toContain('恢复状态图')
  expect(detail.text()).toContain('数据库层')
  await detail.get('[data-assessment-locate="q1"]').trigger('click')
  expect(wrapper.get('[data-question-group]').attributes('open')).toBeDefined()
  expect(wrapper.get('[data-answer="q1"]').text()).toContain('数据库超时后返回错误')
})
it('旧报告保留摘要，无观察时展示克制的空态', async () => {
  await show()
  expect(wrapper.find('[data-topic-coverage]').exists()).toBe(false)
  expect(wrapper.find('[data-detailed-assessment]').exists()).toBe(false)
  expect(wrapper.get('[data-overview]').text()).toContain('本场回答保存完整')
  const value: any=fixture()
  value.report.detailedAssessment={version:1,overall:{text:'样本不足，仅能确认本场已作答。',questionIds:[]},strengths:[],improvements:[],nextSteps:[]}
  await wrapper.setProps({session:value})
  expect(wrapper.get('[data-detailed-assessment]').text()).toContain('尚无足够证据提炼亮点')
  expect(wrapper.get('[data-detailed-assessment]').text()).toContain('暂无可确认的改进项')
})

it('考点回顾区分新练复习和已答未答，并定位到对应原始问答', async () => {
  const value: any = fixture()
  value.report.topicCoverage = { askedCount: 2, answeredCount: 1, newCount: 1, reviewCount: 1, items: [
    { pointId: 'tool.idempotency', theme: 'AGENT_HARNESS_TOOL', title: '工具副作用与幂等边界', questionId: 'q2', rootQuestionId: 'root', review: true, answered: true },
    { pointId: 'context.selection', theme: 'AGENT_CONTEXT_MEMORY', title: '上下文筛选边界', questionId: 'q3', rootQuestionId: 'next', review: false, answered: false },
  ] }
  await show(value)
  const coverage = wrapper.get('[data-topic-coverage]')
  expect(coverage.text()).toContain('本场考点回顾')
  expect(coverage.text()).toContain('已问 2')
  expect(coverage.text()).toContain('已答 1')
  expect(coverage.text()).toContain('新练 1')
  expect(coverage.text()).toContain('复习 1')
  const items = coverage.findAll('[data-topic-point]')
  expect(items[0]!.text()).toContain('工具与执行控制')
  expect(items[0]!.text()).toContain('工具副作用与幂等边界')
  expect(items[0]!.text()).toContain('复习')
  expect(items[0]!.text()).toContain('已答')
  expect(items[1]!.text()).toContain('上下文与记忆')
  expect(items[1]!.text()).toContain('新练')
  expect(items[1]!.text()).toContain('未答')
  await coverage.get('[data-topic-locate="q2"]').trigger('click')
  expect(wrapper.get('[data-question-group]').attributes('open')).toBeDefined()
  expect(wrapper.get('[data-answer="q2"]').text()).toContain('使用唯一键避免重复写入')
  await coverage.get('[data-topic-locate="q3"]').trigger('click')
  expect(wrapper.findAll('[data-question-group]')[1]!.attributes('open')).toBeDefined()
  expect(wrapper.get('[data-answer="q3"]').text()).toContain('未作答')
})

it('正式问答状态不展示复盘中的具体考点提示', async () => {
  const value: any = fixture(); value.status = 'IN_PROGRESS'
  value.report.topicCoverage = { askedCount: 1, answeredCount: 0, newCount: 1, reviewCount: 0, items: [{ pointId: 'hidden.point', theme: 'AGENT_RAG', title: '本题隐藏考查意图', questionId: 'q1', rootQuestionId: 'root', review: false, answered: false }] }
  await show(value)
  expect(wrapper.find('[data-topic-coverage]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('本题隐藏考查意图')
})

 it('快速复盘按原答展示折叠的追问依据，不呈现四阶段空壳', async () => {
  const value: any = fixture(); value.sessionType = 'QUICK'; delete value.report.stages
  value.report.diagnostics = [{ questionId: 'q1', answerMessageId: 'm2', action: 'FOLLOW_UP', diagnosis: { category: 'INCOMPLETE', coveredPoints: ['说明了失败返回'], answerQuote: '数据库超时后返回错误', gap: '没有说明重试边界', nextGoal: '核对幂等约束', reason: '区分一次失败与重复写入风险' } }]
  await show(value)
  expect(wrapper.find('[aria-label="本场四阶段完成情况"]').exists()).toBe(false)
  const diagnosis = wrapper.get('[data-followup-diagnostics]')
  expect(diagnosis.element.tagName).toBe('DETAILS')
  expect(diagnosis.attributes('open')).toBeUndefined()
  expect(diagnosis.text()).toContain('数据库超时后返回错误')
  expect(diagnosis.text()).toContain('没有说明重试边界')
  await diagnosis.get('[data-diagnosis-locate="q1"]').trigger('click')
  expect(wrapper.get('[data-answer="q1"]').text()).toContain('数据库超时后返回错误')
})
