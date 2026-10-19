import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import router from './router'

beforeEach(() => { vi.stubGlobal('scrollTo', vi.fn()) })
afterEach(async () => { await router.replace('/'); await flushPromises(); vi.unstubAllGlobals() })

it('快速练习入口独立保留配置参数和锚点', async () => {
  await router.push('/configure/quick?role=Java&level=SENIOR#prepare')
  expect(router.currentRoute.value.path).toBe('/configure/quick')
  expect(router.currentRoute.value.query).toEqual({ role: 'Java', level: 'SENIOR' })
  expect(router.currentRoute.value.hash).toBe('#prepare')
  await router.push('/interviews/same-id')
  expect(router.currentRoute.value.path).toBe('/interviews/same-id')
  await router.push('/history/interviews/same-id/replay')
  expect(router.currentRoute.value.path).toBe('/history/interviews/same-id/replay')
})

it.each([
  '/configure/quick?trainingLinkId=linked&role=Java',
  '/configure/resume', '/configure/project', '/sessions/same-id', '/reports/same-id', '/history/same-id/replay',
])('统一入口保留原有配置、关联训练与历史路由：%s', async path => {
  await router.push(path)
  expect(router.currentRoute.value.fullPath).toBe(path)
})

it.each(['/interview-v3', '/interview-v3/same-id', '/history/v3/same-id/replay'])('历史综合地址仍匹配同一业务页面：%s', async path => {
  await router.push(path)
  expect(router.currentRoute.value.fullPath).toBe(path)
  expect(router.currentRoute.value.matched.at(-1)?.aliasOf).toBeDefined()
})
