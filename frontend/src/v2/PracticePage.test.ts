import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { it, expect, vi, afterEach } from 'vitest'
import PracticePage from './PracticePage.vue'
const caseRow = { id: 'c1', question: '如何处理失败重试？', caseVersion: 1, contextOrigin: 'LEGACY_CONTEXT', analysisExposure: 'PROVIDED', mock: true, priorDialogue: [], rubric: [] }
async function show() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/practice/:id', component: PracticePage }] })
  await router.push('/practice/c1'); await router.isReady()
  const wrapper = mount(PracticePage, { global: { plugins: [router] } }); await flushPromises(); return wrapper
}
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('原始回答与新的独立作答分开呈现，不将原答填入新回答框', async () => {
  vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify(url.endsWith('/attempts?limit=20') ? { items: [{ id: 'original', ordinal: 0, status: 'IMPORTED', answerText: '原始回答不能覆盖', effectiveExposure: 'UNKNOWN' }], nextCursor: null } : url.includes('evaluation-groups') ? { items: [], nextCursor: null } : caseRow)))
  const wrapper = await show()
  expect(wrapper.text()).toContain('原始回答不能覆盖')
  expect(wrapper.text()).toContain('解析已提供')
  expect(wrapper.find('[data-action="new-attempt"]').exists()).toBe(true)
})
it('页面草稿在服务端读取后保留，版本冲突同时展示服务端文本', async () => {
  sessionStorage.setItem('v2:answer:d1', '本页尚未保存的新文本')
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') return new Response(JSON.stringify({ code: 'V2_VERSION_CONFLICT', message: '另一页面已保存，请核对两份文本' }), { status: 409 })
    if (url.includes('/practice-attempts/')) return new Response(JSON.stringify({ id: 'd1', caseId: 'c1', status: 'DRAFT', revision: 1, answerText: '服务端保存的旧文本', effectiveExposure: 'PROVIDED' }))
    return new Response(JSON.stringify(url.includes('/attempts?') ? { items: [{ id: 'd1', ordinal: 1, status: 'DRAFT', answerText: '服务端保存的旧文本', revision: 1 }], nextCursor: null } : url.includes('evaluation-groups') ? { items: [], nextCursor: null } : caseRow))
  })
  const wrapper = await show(); await wrapper.find('[data-attempt="d1"]').trigger('click'); await flushPromises()
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('本页尚未保存的新文本')
  await wrapper.find('[data-action="save-draft"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('另一页面已保存')
  expect(wrapper.text()).toContain('服务端保存的旧文本')
  expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('本页尚未保存的新文本')
})
