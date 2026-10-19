import { afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Configure from './FocusedInterviewConfigure.vue'
afterEach(() => vi.unstubAllGlobals())
it('专项重练保留原来的岗位、级别与薄弱点', async () => {
  const fetcher = vi.fn().mockImplementation(async (_path, options) => new Response(options?.method === 'POST' ? '{"id":"created"}' : '[]'))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/configure/:mode', component: Configure }, { path: '/sessions/:id', component: { template: '<div />' } }] })
  await router.push({ path: '/configure/quick', query: { role: 'Java 后端工程师', level: 'SENIOR', focus: '事务隔离' } }); await router.isReady()
  const wrapper = mount(Configure, { global: { plugins: [router] } }); await flushPromises()
  expect((wrapper.get('#role-select').element as HTMLSelectElement).value).toBe('Java 后端工程师')
  expect((wrapper.get('#level').element as HTMLSelectElement).value).toBe('SENIOR')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  const submitted = fetcher.mock.calls.find(([, options]) => options?.method === 'POST')
  expect(JSON.parse(submitted![1].body)).toMatchObject({ role: 'Java 后端工程师', level: 'SENIOR', focus: '事务隔离' })
  wrapper.unmount()
})
