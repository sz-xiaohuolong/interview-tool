import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AppLayout from '../../components/layout/AppLayout.vue'
it('岗位搜索与求职管理为独立导航，只有当前入口高亮', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/job-search', component: { template: '<div />' } }] }); await router.push('/job-search'); await router.isReady()
  const component = mount(AppLayout, { props: { providerLabel: '', mock: false, configured: true }, global: { plugins: [router], stubs: { ModelSettingsDialog: true, DailyQuote: true } } })
  expect(component.get('a[href="/job-search"]').attributes('aria-current')).toBe('page')
  expect(component.get('a[href="/job-applications"]').attributes('aria-current')).toBeUndefined()
  component.unmount()
})
