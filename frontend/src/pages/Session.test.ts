import { beforeEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SessionPage from './Session.vue'
import type { Session } from '../types'
const mocks = vi.hoisted(() => ({ request: vi.fn(), post: vi.fn(), push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'test-session' } }), useRouter: () => ({ push: mocks.push }) }))
vi.mock('../api', () => ({ request: mocks.request, post: mocks.post, errorMessage: (e: Error) => e.message }))
const first: Session = { id: 'test-session', status: 'IN_PROGRESS', mode: 'QUICK', role: 'Java 后端', level: 'JUNIOR', durationMinutes: 5, style: 'NORMAL', createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', endedAt: null, questionIndex: 0, followUpCount: 0, totalQuestions: 3, currentTopic: '事务', messages: [{ id: 'q1', role: 'ASSISTANT', content: '怎样实现事务？', questionIndex: 0, followUp: false, createdAt: '2026-10-15T00:00:00Z' }], mock: true, error: '', pendingAction: '', reportStatus: 'NONE', contextTruncated: false }
const after = (answer: string): Session => ({ ...first, followUpCount: 1, messages: [...first.messages, { id: 'a1', role: 'USER', content: answer, questionIndex: 0, followUp: false, createdAt: first.createdAt }, { id: 'q2', role: 'ASSISTANT', content: '如何验证回滚？', questionIndex: 0, followUp: true, createdAt: first.createdAt }] })
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear() })
function page() { return mount(SessionPage, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }) }
describe('回答恢复与防重', () => {
  it('不支持听写时保留文字输入并明确提示浏览器兼容边界', async () => {
    mocks.request.mockResolvedValue(first)
    const wrapper = page(); await flushPromises()
    await wrapper.get('textarea').setValue('原有文字回答')
    expect(wrapper.text()).toContain('当前浏览器不支持语音输入')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('原有文字回答')
    wrapper.unmount()
  })
  it('另一个标签页先回答后，过期提交失败不会丢失当前草稿', async () => {
    mocks.request.mockResolvedValueOnce(first).mockResolvedValueOnce(after('另一个标签页的答案'))
    mocks.post.mockRejectedValue(new Error('该问题已更新'))
    const wrapper = page(); await flushPromises()
    await wrapper.get('textarea').setValue('尚未保存的完整长回答')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('尚未保存的完整长回答')
    expect(wrapper.text()).toContain('该问题已更新'); wrapper.unmount()
  })
  it('网络响应失败但本次回答已经保存时，恢复后不会保留待重复发送的草稿', async () => {
    mocks.request.mockResolvedValueOnce(first).mockResolvedValueOnce(after('我的已保存答案'))
    mocks.post.mockRejectedValue(new Error('网络中断'))
    const wrapper = page(); await flushPromises()
    await wrapper.get('textarea').setValue('我的已保存答案'); await wrapper.get('form').trigger('submit'); await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(sessionStorage.getItem('interview-pending-test-session')).toBeNull(); wrapper.unmount()
  })
  it('生成期间重复提交只发出一次回答请求', async () => {
    mocks.request.mockResolvedValue(first); mocks.post.mockReturnValue(new Promise(() => {}))
    const wrapper = page(); await flushPromises()
    await wrapper.get('textarea').setValue('测试防重'); await wrapper.get('form').trigger('submit'); await wrapper.get('form').trigger('submit')
    expect(mocks.post).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
})
