import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from './AppLayout.vue'

let wrapper: VueWrapper | undefined
let saved: Record<string, any>
let writes: { path: string; body: any }[]
let failed = ''
const initial = () => ({ revision: 3, source: 'STARTUP', preset: 'CUSTOM', baseUrl: 'https://old.example/v1', model: 'old-model', fastModel: '', reasoningEffort: '', fastReasoningEffort: '', apiKeyConfigured: true, configured: true, provider: 'openai' })
beforeEach(() => {
  saved = initial(); writes = []; failed = ''
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url !== '/api/model-config' && !url.startsWith('/api/model-config/')) throw new Error(`未预期的请求：${url}`)
    if (!options?.method || options.method === 'GET') return new Response(JSON.stringify(saved))
    writes.push({ path: url, body: JSON.parse(String(options.body)) })
    if (failed) return new Response(JSON.stringify({ message: '合成服务暂时不可用', code: 'MODEL_HTTP_ERROR' }), { status: 503 })
    if (url.endsWith('/models')) return new Response(JSON.stringify({ models: [{ id: 'list-model' }] }))
    if (url.endsWith('/test')) return new Response(JSON.stringify({ jsonSupported: true, elapsedMs: 83, usage: { promptTokens: 11, completionTokens: 3, totalTokens: 14 } }))
    saved = url.endsWith('/reset') ? { ...initial(), revision: 5 } : { ...saved, ...writes.at(-1)?.body, revision: 4, source: 'CUSTOM', apiKey: undefined }
    return new Response(JSON.stringify(saved))
  })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals() })
async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  wrapper = mount(AppLayout, { attachTo: document.body, props: { providerLabel: '模型已配置 · old-model', mock: false, configured: true }, global: { plugins: [router] } })
  await wrapper.get('[data-model-settings]').trigger('click'); await flushPromises()
}
function field(id: string) { const el = document.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`); expect(el, `缺少${id}`).toBeTruthy(); return el! }
async function fill(id: string, value: string) { const el = field(id); el.value = value; el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); await flushPromises() }
async function click(action: string) { const button = document.querySelector<HTMLButtonElement>(`[data-action="${action}"]`); expect(button).toBeTruthy(); button!.click(); await flushPromises() }

it('右上角打开全站弹窗，读取不请求模型且密钥为空', async () => {
  await open()
  expect(document.querySelector('[role="dialog"]')?.textContent).toContain('模型配置')
  expect(field('model-id').value).toBe('old-model')
  expect(field('model-key').value).toBe('')
  expect((field('model-key') as HTMLInputElement).type).toBe('password')
  expect(writes).toHaveLength(0)
})
it('关闭丢弃未保存配置和密钥，重新打开读取已保存值', async () => {
  await open(); await fill('model-key', 'unsaved-synthetic-secret'); await fill('model-id', 'draft-model')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭弹窗"]')!.click(); await flushPromises()
  expect(document.querySelector('[role="dialog"]')).toBeNull()
  await wrapper!.get('[data-model-settings]').trigger('click'); await flushPromises()
  expect(field('model-key').value).toBe(''); expect(field('model-id').value).toBe('old-model')
  expect(writes).toHaveLength(0)
  expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('unsaved-synthetic-secret')
})
it('保存草稿携带版本并立即更新顶栏，保存不调用探测接口', async () => {
  await open(); await fill('model-id', 'new-model'); await click('model-save')
  expect(writes).toEqual([{ path: '/api/model-config', body: expect.objectContaining({ expectedRevision: 3, model: 'new-model', apiKey: '' }) }])
  expect(wrapper!.get('[data-model-settings]').text()).toContain('new-model')
  expect(wrapper!.emitted('configuration-changed')).toHaveLength(1)
})
it('模型列表失败不影响手填，JSON测试只检测草稿而不保存', async () => {
  await open(); await fill('model-id', 'manual-model'); failed = 'models'
  await click('model-models')
  expect(field('model-id').value).toBe('manual-model')
  expect(document.body.textContent).toContain('合成服务暂时不可用')
  failed = ''; await click('model-test')
  expect(writes.map(v => v.path)).toEqual(['/api/model-config/models', '/api/model-config/test'])
  expect(writes[1]?.body.model).toBe('manual-model')
  expect(document.body.textContent).toContain('JSON')
  expect(document.body.textContent).toContain('14')
  expect(saved.model).toBe('old-model')
})
it('切换DeepSeek预设清空旧密钥且要求填写新连接密钥', async () => {
  await open(); await fill('model-key', 'old-draft-key'); await fill('model-preset', 'DEEPSEEK')
  expect(field('model-url').value).toBe('https://api.deepseek.com/v1')
  expect(field('model-key').value).toBe('')
  await fill('model-id', 'provider-current-id')
  expect(document.querySelector<HTMLButtonElement>('[data-action="model-save"]')?.disabled).toBe(true)
  await fill('model-key', 'fresh-synthetic-key')
  expect(document.querySelector<HTMLButtonElement>('[data-action="model-save"]')?.disabled).toBe(false)
})
it('保存失败保留输入供重试，恢复启动配置需要显式确认', async () => {
  await open(); await fill('model-id', 'draft-model'); failed = 'save'; await click('model-save')
  expect(field('model-id').value).toBe('draft-model')
  expect(wrapper!.emitted('configuration-changed')).toBeUndefined()
  vi.stubGlobal('confirm', () => false); await click('model-reset'); expect(writes).toHaveLength(1)
  vi.stubGlobal('confirm', () => true); failed = ''; await click('model-reset')
  expect(writes.at(-1)).toEqual({ path: '/api/model-config/reset', body: { expectedRevision: 3 } })
  expect(field('model-id').value).toBe('old-model')
})

it('关闭后迟到的读取不能覆盖新打开的草稿', async () => {
  const replies: ((value: Response) => void)[] = []
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => replies.push(resolve)))
  await open()
  document.querySelector<HTMLButtonElement>('[aria-label="关闭弹窗"]')!.click(); await flushPromises()
  await wrapper!.get('[data-model-settings]').trigger('click'); await flushPromises()
  replies[1]!(new Response(JSON.stringify({ ...initial(), model: 'latest-model' }))); await flushPromises()
  await fill('model-id', 'user-draft')
  replies[0]!(new Response(JSON.stringify(initial()))); await flushPromises()
  expect(field('model-id').value).toBe('user-draft')
  expect(field('model-key').value).toBe('')
})

it('提交保存后关闭弹窗，成功结果仍刷新全站配置', async () => {
  await open(); await fill('model-id', 'late-saved-model')
  let finish!: (value: Response) => void
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => { finish = resolve }))
  await click('model-save')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭弹窗"]')!.click(); await flushPromises()
  finish(new Response(JSON.stringify({ ...initial(), revision: 4, model: 'late-saved-model' }))); await flushPromises()
  expect(wrapper!.get('[data-model-settings]').text()).toContain('late-saved-model')
  expect(wrapper!.emitted('configuration-changed')).toHaveLength(1)
  expect(document.querySelector('[role="dialog"]')).toBeNull()
})
