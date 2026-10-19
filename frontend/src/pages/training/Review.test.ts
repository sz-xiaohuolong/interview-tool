import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ReviewPage from './Review.vue'

afterEach(() => vi.unstubAllGlobals())
beforeEach(() => sessionStorage.clear())
const source = { id: 'r1', revision: 0, question: '如何保证订单一致性？', status: 'PENDING_CONFIRMATION', sourceSessionId: 's1', questionIndex: 0, sourcePrecision: 'QUESTION_ONLY', mock: true, skillIds: [], findings: [{ id: 'f1', kind: 'uncertain', text: '资料不足，无法判断事务行为', confirmed: false, disputed: false }], dialogue: [{ id: 'q1', role: 'ASSISTANT', content: '如何保证订单一致性？', questionIndex: 0, followUp: false }, { id: 'm1', role: 'USER', content: '这是我的原始回答。' }] }
async function show(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/review', component: ReviewPage }, { path: '/review/:id', component: ReviewPage }, { path: '/practice/:id', component: { template: '<div>原题二答</div>' } }, { path: '/sessions/:id', component: { template: '<div>作答页面</div>' } }, { path: '/reports/:id', component: { template: '<div>复盘页面</div>' } }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(ReviewPage, { global: { plugins: [router] } }); await flushPromises(); return wrapper
}
it('空复习队列给出原有面试入口，不显示虚构项目', async () => {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ items: [], nextCursor: null })))
  const wrapper = await show('/review')
  expect(wrapper.text()).toContain('还没有复习项')
  expect(wrapper.find('a[href="/configure/quick"]').exists()).toBe(true)
})
it('确认冲突时保留原始证据，并显示重新读取入口', async () => {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') return new Response(JSON.stringify({ code: 'V2_VERSION_CONFLICT', message: '另一页面已修改，请保留本页内容', currentRevision: 1 }), { status: 409 })
    if (url.includes('/practice-cases?')) return new Response(JSON.stringify({ items: [], nextCursor: null }))
    return new Response(JSON.stringify(url.includes('/skills') ? { catalog: { skills: [] } } : source))
  })
  const wrapper = await show('/review?id=r1')
  await wrapper.find('[data-action="practice"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('另一页面已修改')
  expect(wrapper.text()).toContain('这是我的原始回答。')
  expect(wrapper.find('[data-action="reload"]').exists()).toBe(true)
})
it('新版没有发现时可核对旧版本和历史标签，不提供空确认', async () => {
  const old = { ...source, status: 'DUE', reportHash: 'old-report', skillIds: ['JAVA.TRANSACTIONS'] }
  const updated = { ...source, findings: [], reportHash: 'new-report', sourceVersions: [old] }
  vi.stubGlobal('fetch', async (url: string) => {
    const result = url.includes('/skills') ? { catalog: { skills: [{ id: 'JAVA.TRANSACTIONS', name: 'Java 事务' }] } } : url.includes('/history?') ? { items: [{ id: 'op', createdAt: '2026-10-15T00:00:00Z' }], nextCursor: null } : url.endsWith('/history/op') ? old : updated
    return new Response(JSON.stringify(result))
  })
  const wrapper = await show('/review?id=r1')
  expect(wrapper.find('[data-action="confirm"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('这版报告没有待练发现')
  const versions = wrapper.findAll('details').find(d => d.get('summary').text().startsWith('查看来源报告版本'))!
  expect(versions.text()).toContain('资料不足，无法判断事务行为')
  const history = wrapper.findAll('details').find(d => d.get('summary').text() === '查看确认、补证与安排历史')!
  ;(history.element as HTMLDetailsElement).open = true
  await history.trigger('toggle'); await flushPromises()
  await history.get('button').trigger('click'); await flushPromises()
  expect(history.text()).toContain('当时标签：Java 事务')
  wrapper.unmount()
})

const gap = { id: 'gap', kind: 'missing', text: '遗漏异常回滚边界', confirmed: false, disputed: false }
const due = { ...source, status: 'DUE', findings: [{ ...gap, confirmed: true }], role: 'Java 后端', level: 'JUNIOR' }
const caseRow = { id: 'c1', sourceKind: 'SESSION', sourceId: 's1', messageId: 'q1', questionIndex: 0, mock: true, createdAt: '2026-10-18T00:00:00Z' }
function mockReview(entries: any[], extra: (url: string, init?: RequestInit) => unknown = () => undefined) {
  const writes: { url: string; body: any }[] = []
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== 'GET') writes.push({ url, body: JSON.parse(String(init.body)) })
    const custom = extra(url, init)
    if (custom instanceof Response) return custom
    if (custom !== undefined) return new Response(JSON.stringify(custom))
    if (url === '/api/sessions') return new Response('[]')
    if (url.includes('/practice-cases?') || url.includes('/attempts?')) return new Response(JSON.stringify({ items: [], nextCursor: null }))
    if (url.includes('/review-items?')) return new Response(JSON.stringify({ items: entries, nextCursor: null }))
    if (url.includes('/skills')) return new Response(JSON.stringify({ catalog: { skills: [] } }))
    if (url.includes('/review-items/')) return new Response(JSON.stringify(entries.find(e => url.includes(e.id)) || entries[0]))
    throw new Error(`未预期请求：${url}`)
  })
  return writes
}
it('卡片使用主问题创建原题案例，不创建新面试或使用追问 ID', async () => {
  const entry = { ...due, dialogue: [...due.dialogue, { id: 'followup', role: 'ASSISTANT', followUp: true, questionIndex: 0, content: '追问' }, { id: 'follow-answer', role: 'USER', content: '追问回答' }] }
  const writes = mockReview([entry], (url, init) => init?.method === 'POST' && url.endsWith('/practice-cases') ? caseRow : undefined)
  const wrapper = await show('/review')
  await wrapper.get('[data-action="practice"]').trigger('click'); await flushPromises()
  expect(wrapper.vm.$route.path).toBe('/practice/c1')
  expect(writes.map(w => w.url)).toEqual(['/api/training/review-items/r1/actions', '/api/training/practice-cases'])
  expect(writes[1]!.body).toMatchObject({ sourceKind: 'SESSION', sourceId: 's1', messageId: 'q1' })
  wrapper.unmount()
})
it('新发现静默确认并开始原题练习，异议不入选', async () => {
  let current = { ...due, status: 'PENDING_CONFIRMATION', findings: [gap, { ...gap, id: 'disputed', disputed: true }] }
  const writes = mockReview([current], (url, init) => {
    if (init?.method !== 'POST') return
    const body = JSON.parse(String(init.body))
    if (body.action === 'CONFIRM') current = { ...current, status: 'DUE', revision: 1 }
    if (body.action === 'START') current = { ...current, status: 'IN_PROGRESS', revision: 2 }
    return body.action ? current : caseRow
  })
  const wrapper = await show('/review')
  await wrapper.get('[data-action="practice"]').trigger('click'); await flushPromises()
  expect(wrapper.vm.$route.path).toBe('/practice/c1')
  expect(writes[0]!.body).toMatchObject({ action: 'CONFIRM', findingIds: ['gap'] })
  expect(writes.some(w => w.url === '/api/sessions')).toBe(false)
  wrapper.unmount()
})
it('跨页找回原题案例和草稿，反复继续只导航且不创建任何资源', async () => {
  const writes = mockReview([due], (url) => {
    if (url.includes('/practice-cases?')) return url.includes('cursor=next') ? { items: [caseRow], nextCursor: null } : { items: [{ ...caseRow, id: 'follow', messageId: 'followup' }], nextCursor: 'next' }
    if (url.includes('/c1/attempts?')) return { items: [{ id: 'draft1', ordinal: 1, status: 'DRAFT' }], nextCursor: null }
  })
  const wrapper = await show('/review')
  for (let i = 0; i < 2; i++) {
    await wrapper.get('.review-resume button').trigger('click'); await flushPromises()
    expect(wrapper.vm.$route.path).toBe('/practice/c1')
    await wrapper.vm.$router.push('/review'); await flushPromises()
  }
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('仅有旧 IN_PROGRESS 状态而没有关联资源时回到待复习', async () => {
  mockReview([{ ...due, status: 'IN_PROGRESS' }])
  const wrapper = await show('/review')
  expect(wrapper.find('.review-resume').exists()).toBe(false)
  expect(wrapper.get('.review-items [data-action="practice"]').text()).toContain('开始重练')
  wrapper.unmount()
})
it('旧关联会话从现有只读接口恢复，继续不产生新会话', async () => {
  const writes = mockReview([{ ...due, status: 'IN_PROGRESS' }], url => {
    if (url === '/api/sessions') return [{ id: 'old-session', status: 'IN_PROGRESS', mock: true }]
    if (url.includes('/training-links?sessionId=old-session')) return [{ id: 'old-link', sourceKind: 'REVIEW', sourceId: 'r1', sessionId: 'old-session' }]
  })
  const wrapper = await show('/review')
  await wrapper.get('.review-resume button').trigger('click'); await flushPromises()
  expect(wrapper.vm.$route.path).toBe('/sessions/old-session')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('已完成原题练习进入完成标签，查看结果不触发新草稿', async () => {
  mockReview([{ ...due, status: 'IN_PROGRESS' }], url => {
    if (url.includes('/practice-cases?')) return { items: [caseRow], nextCursor: null }
    if (url.includes('/c1/attempts?')) return { items: [{ id: 'ready1', ordinal: 1, status: 'READY', submittedAt: '2026-10-18T01:00:00Z' }], nextCursor: null }
  })
  const wrapper = await show('/review')
  await wrapper.get('#review-tab-done').trigger('click')
  expect(wrapper.find('.review-items a[href="/practice/c1?attemptId=ready1"]').exists()).toBe(true)
  wrapper.unmount()
})
it('双标签跨页合并，未知与归档放在折叠区', async () => {
  mockReview([due], url => url.includes('/review-items?') ? url.includes('cursor=second') ? { items: [{ ...due, id: 'done', status: 'RESOLVED', question: '已练过的题' }], nextCursor: null } : { items: [{ ...due, id: 'new', status: 'PENDING_CONFIRMATION' }, due, { ...source, id: 'unknown' }, { ...due, id: 'archived', status: 'ARCHIVED' }], nextCursor: 'second' } : undefined)
  const wrapper = await show('/review')
  expect(wrapper.findAll('[role="tab"]')).toHaveLength(2)
  expect(wrapper.get('#review-tab-todo').text()).toContain('2')
  expect(wrapper.get('.review-more').text()).toContain('待核实')
  await wrapper.get('#review-tab-done').trigger('click')
  expect(wrapper.get('.review-items').text()).toContain('已练过的题')
  wrapper.unmount()
})
it('缺少已回答的主问题时明确拒绝，不猜测问题或新建会话', async () => {
  const writes = mockReview([{ ...due, dialogue: due.dialogue.filter(m => m.role === 'USER') }])
  const wrapper = await show('/review')
  await wrapper.get('[data-action="practice"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('原问题')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('全部结论已有异议时不提供开练，保留移除入口', async () => {
  mockReview([{ ...due, findings: [{ ...gap, disputed: true }] }])
  const wrapper = await show('/review/r1')
  expect(wrapper.find('[data-action="practice"]').exists()).toBe(false)
  expect(wrapper.findAll('button').some(b => b.text() === '移出待复习')).toBe(true)
  wrapper.unmount()
})
it('练习归档后不再提供继续入口，原文与恢复入口保留', async () => {
  mockReview([{ ...due, status: 'IN_PROGRESS' }], (url, init) => {
    if (url.includes('/practice-cases?')) return { items: [caseRow], nextCursor: null }
    if (url.includes('/c1/attempts?')) return { items: [{ id: 'draft1', ordinal: 1, status: 'DRAFT' }], nextCursor: null }
    if (init?.method === 'POST') return { ...due, status: 'ARCHIVED', revision: 1 }
  })
  const wrapper = await show('/review/r1')
  expect(wrapper.findAll('button').some(b => b.text().includes('继续作答'))).toBe(true)
  await wrapper.findAll('button').find(b => b.text() === '移出待复习')!.trigger('click'); await flushPromises()
  expect(wrapper.findAll('button').some(b => b.text().includes('继续作答'))).toBe(false)
  expect(wrapper.text()).toContain('恢复归档项')
  expect(wrapper.text()).toContain('这是我的原始回答。')
  wrapper.unmount()
})
