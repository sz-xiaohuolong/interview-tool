import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import ReviewPage from './Review.vue'

const entry = { id: 'r1', sourceSessionId: 's1', questionIndex: 0, mock: true, status: 'WAITING_EVIDENCE', findings: [], dialogue: [{ id: 'q1', role: 'ASSISTANT', questionIndex: 0, content: '原题' }, { id: 'u1', role: 'USER', content: '原答' }, { id: 'follow', role: 'ASSISTANT', questionIndex: 0, followUp: true, content: '追问' }, { id: 'u2', role: 'USER', content: '追问原答' }] }
const sourceCase = { id: 'c1', sourceKind: 'SESSION', sourceId: 's1', questionIndex: 0, messageId: 'q1', mock: true }
async function show(path = '/review/r1') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/review', component: ReviewPage }, { path: '/review/:id', component: ReviewPage }, { path: '/practice/:id', component: { template: '<div>作答</div>' } }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(ReviewPage, { global: { plugins: [router] } }); await flushPromises()
  return { wrapper, router }
}
function mockReview(extra: (url: string, method: string) => unknown = () => undefined) {
  const writes: { url: string; body: any }[] = []
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    if (method !== 'GET') writes.push({ url, body: JSON.parse(String(init?.body)) })
    const custom = extra(url, method)
    if (custom instanceof Response) return custom
    if (custom !== undefined) return new Response(JSON.stringify(custom))
    if (url === '/api/training/review-items/r1') return new Response(JSON.stringify(entry))
    if (url.startsWith('/api/training/practice-cases?sourceId=s1&')) return new Response(JSON.stringify({ items: [], nextCursor: null }))
    if (method === 'POST' && url === '/api/training/practice-cases') return new Response(JSON.stringify(sourceCase))
    throw new Error(`未约定请求：${method} ${url}`)
  })
  return writes
}
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('旧复习地址直接进入原题作答，不要求确认发现或创建面试', async () => {
  const writes = mockReview()
  const { wrapper, router } = await show()
  expect(router.currentRoute.value.path).toBe('/practice/c1')
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ url: '/api/training/practice-cases', body: { sourceKind: 'SESSION', sourceId: 's1', messageId: 'q1' } })
  wrapper.unmount()
})
it('跨页复用同源主问题案例，不误接同ID的新面试或追问', async () => {
  const writes = mockReview(url => url.includes('/practice-cases?') ? url.includes('cursor=next') ? { items: [sourceCase], nextCursor: null } : { items: [{ ...sourceCase, id: 'v3', sourceSessionKind: 'V3' }, { ...sourceCase, id: 'follow', messageId: 'follow' }, { ...sourceCase, id: 'real', mock: false }], nextCursor: 'next' } : undefined)
  const { wrapper, router } = await show()
  expect(router.currentRoute.value.path).toBe('/practice/c1')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('历史attemptId只恢复本题所属案例，并保留结果参数', async () => {
  const writes = mockReview(url => url.includes('/practice-cases?') ? { items: [sourceCase, { ...sourceCase, id: 'follow-case', messageId: 'follow' }], nextCursor: null } : url === '/api/training/practice-attempts/a1' ? { caseId: 'follow-case' } : undefined)
  const { wrapper, router } = await show('/review?id=r1&attemptId=a1')
  expect(router.currentRoute.value.path).toBe('/practice/follow-case')
  expect(router.currentRoute.value.query.attemptId).toBe('a1')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('其他来源的attemptId明确失败，不误接或新建', async () => {
  const writes = mockReview(url => url === '/api/training/practice-attempts/a1' ? { caseId: 'foreign' } : undefined)
  const { wrapper, router } = await show('/review/r1?attemptId=a1')
  expect(router.currentRoute.value.path).toBe('/review/r1')
  expect(wrapper.text()).toContain('不属于当前题目')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it.each(['ARCHIVED', 'PENDING_CONFIRMATION', 'IN_PROGRESS', 'WAITING_EVIDENCE'])('旧状态%s不阻止直接重答，也不修改历史状态', async status => {
  const writes = mockReview(url => url === '/api/training/review-items/r1' ? { ...entry, status } : url.includes('/practice-cases?') ? { items: [sourceCase], nextCursor: null } : undefined)
  const { wrapper, router } = await show()
  expect(router.currentRoute.value.path).toBe('/practice/c1')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('缺少已回答主问题时明确报错，不拿追问代替', async () => {
  const writes = mockReview(url => url === '/api/training/review-items/r1' ? { ...entry, dialogue: entry.dialogue.filter(m => m.id !== 'q1') } : undefined)
  const { wrapper } = await show()
  expect(wrapper.text()).toContain('找不到已有回答的原问题')
  expect(wrapper.find('[data-action="reload"]').exists()).toBe(true)
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('案例准备失败可显式重试，未默默切换其他来源', async () => {
  let calls = 0
  mockReview((url, method) => method === 'POST' && url.endsWith('/practice-cases') && ++calls === 1 ? new Response(JSON.stringify({ message: '准备失败，请重试' }), { status: 503 }) : undefined)
  const { wrapper, router } = await show()
  expect(wrapper.text()).toContain('准备失败')
  await wrapper.get('[data-action="reload"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/practice/c1')
  wrapper.unmount()
})
