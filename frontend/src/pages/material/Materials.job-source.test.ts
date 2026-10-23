import { afterEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Materials from './Materials.vue'
let component: VueWrapper
const time = '2026-10-23T02:00:00Z'
const material = (id: string, name: string, kind = 'JD') => ({ id, name, kind, content: `正文：${name}`, documents: [], createdAt: time, updatedAt: time })
async function render(items: unknown[], path: string) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/materials' && !options?.method) return new Response(JSON.stringify(items))
    throw new Error(`未知请求：${url}`)
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/materials', component: Materials }] }); await router.push(path); await router.isReady()
  component = mount(Materials, { attachTo: document.body, global: { plugins: [router] } }); await flushPromises()
  return { body: new DOMWrapper(document.body), router }
}
afterEach(() => { component?.unmount(); document.body.innerHTML = ''; vi.unstubAllGlobals() })
it('精确 materialId 深链打开目标 JD，原来源和用户编辑事实独立显示', async () => {
  const target = { ...material('same', '准确 JD'), jobSource: { materialId: 'same', source: 'BOSS', sourceId: 'same', url: 'https://www.zhipin.com/job_detail/target.html', fetchedAt: time, contentEdited: true } }
  const { body } = await render([material('resume-same', '其他资料', 'RESUME'), material('another', '另一份 JD'), target], '/materials?kind=JD&materialId=same')
  expect(body.get('[role="dialog"]').text()).toContain('准确 JD'); expect(body.get('[role="dialog"]').text()).not.toContain('另一份 JD')
  expect(body.get('[role="dialog"]').text()).toContain('BOSS 直聘'); expect(body.get('[role="dialog"]').text()).toContain('正文已由用户修改')
  expect(body.get('[data-job-source-link]').attributes('href')).toBe(target.jobSource.url); expect(body.get('[data-job-source-link]').attributes('target')).toBe('_blank')
})
it('同页深链更新重新选中准确资料，已删除资料明确提示而不打开其他项', async () => {
  const { body, router } = await render([material('one', '第一份'), material('two', '第二份')], '/materials?kind=JD&materialId=one')
  await router.push('/materials?kind=JD&materialId=two'); await flushPromises(); expect(body.get('[role="dialog"]').text()).toContain('第二份')
  await router.push('/materials?kind=JD&materialId=gone'); await flushPromises(); expect(body.find('[role="dialog"]').exists()).toBe(false); expect(body.text()).toContain('目标资料不存在或已删除')
})
