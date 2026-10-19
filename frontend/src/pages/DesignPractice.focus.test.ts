import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import DesignPage from './DesignPractice.vue'

let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; sessionStorage.clear(); vi.unstubAllGlobals() })
const session = (ready = false) => ({ id: 'd', revision: 2, state: 'IN_PROGRESS', currentStage: 'CLARIFY', mock: true, scenario: { title: '订单服务设计', description: '合成场景' }, stages: [
  { id: 'CLARIFY', title: '需求澄清', state: ready ? 'FEEDBACK_READY' : 'DRAFT', currentArtifactVersion: ready ? 1 : 0, draft: { goals: '合成目标' }, fieldDefinitions: [{ id: 'goals', label: '目标' }], artifactVersions: [], ...(ready ? { currentArtifact: { version: 1, fields: { goals: '合成目标' }, feedbackStatus: 'READY', feedback: { strengths: [], gaps: [], uncertain: [], tradeoffs: [], openIssues: [], evidenceRefs: [] } } } : {}) },
  { id: 'CONSTRAINTS', title: '约束与容量', state: 'EMPTY', currentArtifactVersion: 0, draft: {}, fieldDefinitions: [{ id: 'constraints', label: '容量假设' }], artifactVersions: [] },
] })
async function show() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/design-practice/:id?', component: DesignPage }] })
  await router.push('/design-practice/d'); await router.isReady()
  wrapper = mount(DesignPage, { attachTo: document.body, global: { plugins: [router] } }); await flushPromises()
  return wrapper
}
it('保存阶段时保留操作按钮，成功后恢复原操作焦点和输入', async () => {
  let resolve!: (response: Response) => void
  vi.stubGlobal('fetch', (url: string, options?: RequestInit) => {
    if (url === '/api/training/design-sessions/d' && !options?.method) return Promise.resolve(new Response(JSON.stringify(session())))
    if (url === '/api/training/design-sessions/d/draft' && options?.method === 'PUT') return new Promise<Response>(done => { resolve = done })
    throw new Error('非预期接口：' + url)
  })
  const view = await show(), save = view.findAll('button').find(b => b.text() === '保存阶段草稿')!
  const element = save.element as HTMLButtonElement
  element.focus(); await save.trigger('click'); await nextTick()
  expect(element.isConnected).toBe(true); expect(element.disabled).toBe(true)
  element.blur()
  resolve(new Response(JSON.stringify({ ...session(), revision: 3 }))); await flushPromises(); await nextTick()
  expect(document.activeElement).toBe(element)
  expect((view.get('#field-goals').element as HTMLTextAreaElement).value).toBe('合成目标')
})
it('明确推进阶段后将焦点交给下一阶段首字段', async () => {
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url === '/api/training/design-sessions/d' && !options?.method) return new Response(JSON.stringify(session(true)))
    if (url === '/api/training/design-sessions/d/advance' && options?.method === 'POST') {
      expect(JSON.parse(options.body as string)).toMatchObject({ expectedVersion: 2, artifactVersion: 1, acknowledgeOpenIssues: false })
      return new Response(JSON.stringify({ ...session(true), revision: 3, currentStage: 'CONSTRAINTS' }))
    }
    throw new Error('非预期接口：' + url)
  })
  const view = await show(), advance = view.get('[data-action="advance-stage"]')
  ;(advance.element as HTMLButtonElement).focus(); await advance.trigger('click'); await flushPromises(); await nextTick()
  const field = view.get('#field-constraints').element as HTMLTextAreaElement
  expect(field.readOnly).toBe(false); expect(document.activeElement).toBe(field)
})
