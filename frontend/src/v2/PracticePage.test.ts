import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { it, expect, vi, afterEach } from 'vitest'
import PracticePage from './PracticePage.vue'

const caseRow = { id: 'c1', sourceKind: 'SESSION', sourceId: 's1', questionIndex: 0, revision: 1, question: '如何处理失败重试？', caseVersion: 1, contextOrigin: 'LEGACY_CONTEXT', analysisExposure: 'PROVIDED', mock: true, priorDialogue: [], rubric: [] }
const original = { id: 'original', ordinal: 0, revision: 0, status: 'IMPORTED', answerText: '原始回答不能覆盖', effectiveExposure: 'UNKNOWN' }
const draft = { id: 'd1', ordinal: 1, revision: 0, status: 'DRAFT', answerText: '', effectiveExposure: 'PROVIDED' }
const ready = { ...draft, status: 'READY', revision: 1, answerText: '本次完整回答', evaluation: { id: 'g1', status: 'READY', result: { checks: [], suggestions: ['单次评估建议'] } } }
const result = { id: 'cmp1', leftAttemptId: 'original', rightAttemptId: 'd1', status: 'READY', comparability: 'SAME_PROTOCOL', result: { comparison: { fixed: [{ checkId: 'COVERAGE', description: '已补齐重试边界', leftEvidenceRefs: [], rightEvidenceRefs: [] }], remaining: [], newIssues: [], suggestions: ['补充表达顺序'] } } }
async function show(path = '/practice/c1', keyed = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/practice/:id', component: PracticePage }, { path: '/review', component: { template: '<div>错题复习</div>' } }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(keyed ? { template: '<router-view :key="$route.fullPath" />' } : PracticePage, { global: { plugins: [router] } }); await flushPromises(); return wrapper
}
function mockPractice(initial = [original], handle: (url: string, init?: RequestInit) => unknown = () => undefined) {
  let attempts: any[] = initial
  const writes: { url: string; method: string; body: any }[] = []
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET', body = init?.body ? JSON.parse(String(init.body)) : null
    if (method !== 'GET') writes.push({ url, method, body })
    const custom = await handle(url, init)
    if (custom instanceof Response) return custom
    if (custom !== undefined) return new Response(JSON.stringify(custom))
    if (method === 'POST' && url.endsWith('/attempts')) { attempts = [...attempts, draft]; return new Response(JSON.stringify(draft)) }
    if (method === 'POST' && url.endsWith('/submit')) { attempts = attempts.map(a => a.id === 'd1' ? ready : a); return new Response(JSON.stringify(ready)) }
    if (method === 'POST' && url.endsWith('/practice-comparisons')) return new Response(JSON.stringify(result))
    if (url.includes('/attempts?') || url.endsWith('/recent-attempts')) return new Response(JSON.stringify({ items: attempts, nextCursor: null }))
    if (url.includes('/evaluation-groups?') || url.includes('/practice-comparisons?')) return new Response(JSON.stringify({ items: [], nextCursor: null }))
    if (url.includes('/review-item-for-source?')) return new Response(JSON.stringify({ id: 'r1' }))
    if (url.includes('/practice-attempts/')) return new Response(JSON.stringify(attempts.find(a => url.includes(a.id)) || draft))
    if (url.endsWith('/practice-cases/c1')) return new Response(JSON.stringify(caseRow))
    throw new Error(`未预期请求：${method} ${url}`)
  })
  return writes
}
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('进入即准备空白新草稿，原答与诊断默认折叠，不要求选择声明或回答', async () => {
  const writes = mockPractice()
  const wrapper = await show()
  expect(wrapper.get('textarea').attributes('readonly')).toBeUndefined()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  expect(writes).toHaveLength(1)
  expect(writes[0]!.body.exposureDeclaration).toBe('UNKNOWN')
  expect(wrapper.find('select').exists()).toBe(false)
  const reference = wrapper.findAll('details').find(d => d.get('summary').text() === '查看上一次回答及原报告诊断')!
  expect(reference.attributes('open')).toBeUndefined()
  expect(reference.text()).toContain('原始回答不能覆盖')
  wrapper.unmount()
})
it('跨页恢复已存在草稿，刷新不重复新建', async () => {
  const writes = mockPractice([original], url => {
    if (url.includes('/attempts?')) return url.includes('cursor=next') ? { items: [draft], nextCursor: null } : { items: [original], nextCursor: 'next' }
    if (url.endsWith('/practice-attempts/d1')) return { ...draft, answerText: '服务端已有草稿' }
  })
  const wrapper = await show()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('服务端已有草稿')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('版本冲突时保留本页输入和服务端文本', async () => {
  sessionStorage.setItem('v2:answer:d1', '本页尚未保存的新文本')
  mockPractice([original, draft], (url, init) => {
    if (init?.method === 'PUT') return new Response(JSON.stringify({ code: 'V2_VERSION_CONFLICT', message: '另一页面已保存，请核对两份文本' }), { status: 409 })
    if (url.endsWith('/practice-attempts/d1')) return { ...draft, answerText: '服务端保存的旧文本' }
  })
  const wrapper = await show()
  await wrapper.get('[data-action="save-draft"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('另一页面已保存')
  expect(wrapper.text()).toContain('服务端保存的旧文本')
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('本页尚未保存的新文本')
  wrapper.unmount()
})
it('READY 后自动以原答与本次回答配对重评，直接呈现结果及两个出口', async () => {
  const writes = mockPractice([original, draft])
  const wrapper = await show()
  await wrapper.get('textarea').setValue('本次完整回答')
  await wrapper.get('[data-action="submit"]').trigger('click'); await flushPromises()
  expect(writes.map(w => w.url)).toEqual(['/api/v2/practice-attempts/d1/submit', '/api/v2/practice-comparisons'])
  expect(writes[1]!.body).toMatchObject({ leftAttemptId: 'original', rightAttemptId: 'd1', mode: 'REEVALUATE_PAIR' })
  expect(wrapper.text()).toContain('已补齐重试边界')
  expect(wrapper.find('[data-action="again"]').exists()).toBe(true)
  expect(wrapper.find('a[href="/review"]').exists()).toBe(true)
  expect(wrapper.vm.$route.query.attemptId).toBe('d1')
  wrapper.unmount()
})
it('结果刷新读取已有对比，不重复调用模型或创建草稿', async () => {
  const writes = mockPractice([original, ready], url => url.includes('/practice-comparisons?') ? { items: [{ ...result, result: undefined }], nextCursor: null } : url.endsWith('/practice-comparisons/cmp1') ? result : undefined)
  const wrapper = await show('/practice/c1?attemptId=d1')
  expect(wrapper.text()).toContain('已补齐重试边界')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('再练一次清空输入，仅准备下一份草稿', async () => {
  const writes = mockPractice([original, ready], (url, init) => {
    if (url.endsWith('/practice-comparisons/cmp1')) return result
    if (url.includes('/practice-comparisons?')) return { items: [result], nextCursor: null }
    if (init?.method === 'POST' && url.endsWith('/attempts')) return { ...draft, id: 'd2', ordinal: 2 }
    if (url.endsWith('/practice-attempts/d2')) return { ...draft, id: 'd2', ordinal: 2 }
  })
  const wrapper = await show('/practice/c1?attemptId=d1')
  await wrapper.get('[data-action="again"]').trigger('click'); await flushPromises()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  expect(wrapper.get('textarea').attributes('readonly')).toBeUndefined()
  expect(wrapper.vm.$route.query.attemptId).toBeUndefined()
  expect(writes.map(w => w.url)).toEqual(['/api/v2/practice-cases/c1/attempts'])
  wrapper.unmount()
})
it('比较失败保留已封存答案，恢复比较不再次提交答案', async () => {
  const writes = mockPractice([original, draft], (url, init) => {
    if (init?.method === 'POST' && url.endsWith('/practice-comparisons')) return new Response(JSON.stringify({ message: '对比服务暂时不可用' }), { status: 503 })
  })
  const wrapper = await show()
  await wrapper.get('textarea').setValue('本次完整回答')
  await wrapper.get('[data-action="submit"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('对比服务暂时不可用')
  expect(wrapper.get('textarea').attributes('readonly')).toBeDefined()
  await wrapper.get('[data-action="retry-comparison"]').trigger('click'); await flushPromises()
  expect(writes.filter(w => w.url.endsWith('/submit'))).toHaveLength(1)
  expect(writes.filter(w => w.url.endsWith('/practice-comparisons'))).toHaveLength(2)
  wrapper.unmount()
})
it('中断评估直接恢复已封存回答，不自动新建或对比', async () => {
  const interrupted = { ...ready, status: 'INTERRUPTED', evaluation: { id: 'g1', status: 'INTERRUPTED', operationId: 'op1' } }
  const writes = mockPractice([original, interrupted])
  const wrapper = await show()
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('本次完整回答')
  expect(wrapper.text()).toContain('查看本次操作')
  expect(writes).toEqual([])
  wrapper.unmount()
})
it('没有原答的故事首次作答只显示本次反馈，不伪造比较', async () => {
  const writes = mockPractice([], url => url.endsWith('/practice-cases/c1') ? { ...caseRow, sourceKind: 'STORY', contextOrigin: 'CONFIRMED_STORY' } : undefined)
  const wrapper = await show()
  await wrapper.get('textarea').setValue('本次完整回答')
  await wrapper.get('[data-action="submit"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('单次评估建议')
  expect(writes.some(w => w.url.endsWith('/practice-comparisons'))).toBe(false)
  wrapper.unmount()
})

it('查询参数引起页面重建时，对比结果仍自动出现且不重复提交', async () => {
  let finish: (() => void) | undefined
  let saved = false
  const writes = mockPractice([original, draft], async (url, init) => {
    if (init?.method === 'POST' && url.endsWith('/practice-comparisons')) {
      await new Promise<void>(resolve => { finish = resolve })
      saved = true
      return result
    }
    if (url.includes('/practice-comparisons?')) return { items: saved ? [result] : [], nextCursor: null }
    if (url.endsWith('/practice-comparisons/cmp1')) return result
  })
  const wrapper = await show('/practice/c1', true)
  await wrapper.get('textarea').setValue('本次完整回答')
  await wrapper.get('[data-action="submit"]').trigger('click'); await flushPromises()
  expect(finish).toBeDefined()
  finish!(); await flushPromises()
  expect(wrapper.text()).toContain('已补齐重试边界')
  expect(writes.filter(w => w.url.endsWith('/submit'))).toHaveLength(1)
  expect(writes.filter(w => w.url.endsWith('/practice-comparisons'))).toHaveLength(1)
  wrapper.unmount()
})
