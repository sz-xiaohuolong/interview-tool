import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import KnowledgeSources from './KnowledgeSources.vue'

it('无正文时仍在详情保留准确失败状态和缓存信息', () => {
  const wrapper = mount(KnowledgeSources, { props: { sources: [], retrieval: [
    { sourceId: 'a', title: '官方资料甲', url: 'https://docs.oracle.com/', status: 'HTTP_ERROR', message: '参考文档返回HTTP 503', httpStatus: 503, cacheUsed: false, stale: false },
    { sourceId: 'b', title: '官方资料乙', url: 'https://docs.oracle.com/', status: 'TIMEOUT', message: '参考文档请求达到10秒上限', cacheUsed: true, stale: true },
  ] } } as any)
  expect(wrapper.get('[data-retrieval-details]').text()).toContain('HTTP 503')
  expect(wrapper.get('[data-retrieval-details]').text()).toContain('TIMEOUT')
  expect(wrapper.get('[data-retrieval-details]').text()).toContain('已使用本地缓存')
  expect(wrapper.get('[data-retrieval-details]').text()).toContain('已过期')
  wrapper.unmount()
})
