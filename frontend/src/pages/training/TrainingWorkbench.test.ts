import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { registerTrainingRoutes } from '../../routing/training'

let wrapper: VueWrapper | undefined
const review = { id: 'r1', revision: 2, question: '原面试问题', status: 'DUE', sourceSessionId: 's1', questionIndex: 0, mock: true, skillIds: [], findings: [{ id: 'f1', kind: 'missing', text: '尚缺失败分支', confirmed: true, disputed: false }], dialogue: [{ id: 'q1', role: 'ASSISTANT', questionIndex: 0, content: '原面试问题' }, { id: 'u1', role: 'USER', content: '原始回答' }] }
function practice(id: string, extra: Record<string, unknown> = {}) {
  return { id, revision: 1, question: `练习 ${id}`, sourceKind: 'SESSION', sourceId: 's1', sourceSessionKind: 'SESSION', questionIndex: 0, messageId: 'q1', mock: true, caseVersion: 1, priorDialogue: [], contextOrigin: 'LEGACY_CONTEXT', analysisExposure: 'PROVIDED', latestAttempt: { id: `draft-${id}`, ordinal: 1, status: 'DRAFT' }, ...extra }
}
async function show(path = '/review', remove?: (url: string) => Promise<Response>, status = review.status, linked?: ReturnType<typeof practice>) {
  let reviews = [{ ...review, status }]
  let cases = [practice('c1'), practice('c2', { sourceSessionKind: 'V3', contextOrigin: 'REVIEW_GAP', questionIndex: 0 }), practice('c3', { sourceKind: 'STORY', sourceId: 'story', contextOrigin: 'CONFIRMED_STORY' })]
  if (linked) cases.push(linked)
  const writes: { url: string; method: string; body: any }[] = []
  const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    const u = new URL(url, 'http://localhost'), method = options?.method || 'GET'
    if (method !== 'GET') {
      writes.push({ url, method, body: JSON.parse(String(options?.body)) })
      if (method === 'DELETE' && url === '/api/training/practice-cases/c2') {
        if (remove) { const result = await remove(url); if (!result.ok) return result }
        cases = cases.filter(c => c.id !== 'c2'); return respond({ id: 'c2' })
      }
      if (method === 'DELETE' && url === '/api/training/review-items/r1') {
        reviews = []; cases = cases.filter(c => c.id !== 'c1'); return respond({ id: 'r1' })
      }
      throw new Error(`出现未约定写请求：${method} ${url}`)
    }
    if (u.pathname === '/api/training/review-items') return respond({ items: reviews, nextCursor: null })
    if (u.pathname === '/api/training/practice-cases') return respond({ items: cases.filter(c => !u.searchParams.has('sourceId') || c.sourceId === u.searchParams.get('sourceId')), nextCursor: null })
    if (u.pathname === '/api/training/review-items/r1') return respond(review)
    if (u.pathname === '/api/training/skills') return respond({ catalog: { skills: [] } })
    const detail = cases.find(c => u.pathname === `/api/training/practice-cases/${c.id}`)
    if (detail) return respond(detail)
    const attemptCase = cases.find(c => u.pathname === `/api/training/practice-cases/${c.id}/attempts`)
    const attempt = (id: string) => ({ id: `draft-${id}`, ordinal: 1, revision: 0, caseId: id, status: 'DRAFT', answerText: '', effectiveExposure: 'UNKNOWN' })
    if (attemptCase) return respond({ items: [attempt(attemptCase.id)], nextCursor: null })
    const selected = cases.find(c => u.pathname === `/api/training/practice-attempts/draft-${c.id}`)
    if (selected) return respond(attempt(selected.id))
    if (u.pathname === '/api/training/review-item-for-source') return respond({ id: 'r1' })
    throw new Error(`出现未约定读请求：${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/reports/:id', component: { template: '<div>原报告</div>' } }] })
  registerTrainingRoutes(router)
  await router.push(path); await router.isReady()
  wrapper = mount(RouterView, { global: { plugins: [router] } }); await flushPromises()
  return { router, writes, fetcher }
}
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals(); sessionStorage.clear() })

it('同一来源的复习项与案例只显示一次，保留综合补练和项目故事', async () => {
  await show()
  expect(wrapper!.findAll('[data-workbench-row]')).toHaveLength(3)
  expect(wrapper!.text()).toContain('错题重练')
  expect(wrapper!.get('[data-workbench-row="review:r1"]').text()).toContain('原面试问题')
  expect(wrapper!.get('[data-workbench-row="case:c2"]').text()).toContain('复盘补练')
  expect(wrapper!.get('[data-workbench-row="case:c3"]').text()).toContain('项目故事')
})

it('同一工作台切换题目后恢复输入草稿，选题不重复创建尝试', async () => {
  const { router, writes } = await show('/practice/c2')
  await wrapper!.get('#new-answer').setValue('尚未提交的恢复测试回答')
  await wrapper!.get('[data-workbench-open="case:c3"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/c3')
  await wrapper!.get('[data-workbench-open="case:c2"]').trigger('click'); await flushPromises()
  expect((wrapper!.get('#new-answer').element as HTMLTextAreaElement).value).toBe('尚未提交的恢复测试回答')
  expect(writes).toEqual([])
  expect(wrapper!.findAll('[data-workbench-list]')).toHaveLength(1)
})

it('删除取消不写入，失败保留记录，成功后只移除选中项', async () => {
  let tries = 0
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(false))
  const { writes } = await show('/practice/c2', async () => ++tries === 1 ? new Response(JSON.stringify({ message: '删除失败，请重试' }), { status: 500 }) : new Response('{}'))
  await wrapper!.get('[data-workbench-delete="case:c2"]').trigger('click'); await flushPromises()
  expect(writes).toEqual([])
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
  await wrapper!.get('[data-workbench-delete="case:c2"]').trigger('click'); await flushPromises()
  expect(wrapper!.get('[role="alert"]').text()).toContain('删除失败')
  expect(wrapper!.find('[data-workbench-row="case:c2"]').exists()).toBe(true)
  await wrapper!.get('[data-workbench-delete="case:c2"]').trigger('click'); await flushPromises()
  expect(wrapper!.find('[data-workbench-row="case:c2"]').exists()).toBe(false)
  expect(wrapper!.find('[data-workbench-row="review:r1"]').exists()).toBe(true)
  expect(wrapper!.find('[data-workbench-row="case:c3"]').exists()).toBe(true)
  expect(writes[0]).toMatchObject({ url: '/api/training/practice-cases/c2', method: 'DELETE', body: { expectedVersion: 1 } })
})

it('删除复习整题不会把已关联案例重新展示成独立项', async () => {
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
  const { writes } = await show('/review/r1')
  await wrapper!.get('[data-workbench-delete="review:r1"]').trigger('click'); await flushPromises()
  expect(wrapper!.findAll('[data-workbench-row]')).toHaveLength(2)
  expect(wrapper!.find('[data-workbench-row="case:c1"]').exists()).toBe(false)
  expect(writes[0]).toMatchObject({ url: '/api/training/review-items/r1', method: 'DELETE', body: { expectedVersion: 2 } })
})


it('搜索与状态筛选在详情和返回列表后保留', async () => {
  const { router } = await show()
  await wrapper!.get('#workbench-search').setValue('项目故事')
  expect(wrapper!.findAll('[data-workbench-row]')).toHaveLength(1)
  await wrapper!.get('[data-workbench-open="case:c3"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/c3')
  await wrapper!.get('.workbench-back').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/review')
  expect((wrapper!.get('#workbench-search').element as HTMLInputElement).value).toBe('项目故事')
  expect(wrapper!.findAll('[data-workbench-row]')).toHaveLength(1)
  await wrapper!.get('#workbench-filter').setValue('done')
  expect(wrapper!.text()).toContain('没有符合筛选条件的题目')
})


it('已结束复习又开始新作答时，列表按当前草稿显示练习中', async () => {
  await show('/review', undefined, 'RESOLVED')
  expect(wrapper!.get('[data-workbench-row="review:r1"]').text()).toContain('练习中')
  expect(wrapper!.get('[data-workbench-row="review:r1"]').text()).not.toContain('已完成')
})

it('整题合并后仍可打开原有追问和作答，不重复创建案例或尝试', async () => {
  const { router, writes } = await show('/review/r1', undefined, review.status, practice('follow', { messageId: 'q-follow', question: '已有追问的异常边界' }))
  expect(wrapper!.findAll('[data-workbench-row]')).toHaveLength(3)
  await wrapper!.get('[data-workbench-case="follow"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/follow')
  expect(router.currentRoute.value.query.attemptId).toBe('draft-follow')
  expect(wrapper!.find('#new-answer').exists()).toBe(true)
  expect(writes).toEqual([])
})

it('练习筛选只有三种状态，旧待核实和归档记录仍可直接进入练习', async () => {
  sessionStorage.setItem('training:workbench:view', JSON.stringify({ filter: 'other' }))
  await show('/review', undefined, 'WAITING_EVIDENCE')
  expect(wrapper!.get('#workbench-filter').findAll('option').map(o => o.attributes('value'))).toEqual(['all', 'todo', 'ongoing', 'done'])
  expect(wrapper!.find('[data-workbench-row="review:r1"]').exists()).toBe(true)
  expect(wrapper!.text()).not.toContain('待核实')
})
