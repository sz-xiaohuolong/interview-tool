import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import StoryPage from './Stories.vue'

const archived = {
  id: 'archived-story', schemaVersion: 1, templateKind: 'INTRO', status: 'ARCHIVED', revision: 3,
  currentVersion: 1, currentVersionId: 'intro-version', materialIds: [], generationState: 'NONE', mock: true,
  templateFields: ['targetRole', 'background', 'experience', 'contribution', 'aspiration'],
  createdAt: '2026-10-16T14:08:49Z', updatedAt: '2026-10-16T14:08:49Z',
}
const kept = { ...archived, id: 'kept-story', templateKind: 'STAR', status: 'DRAFT', revision: 0 }
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function render(remove: (url: string, init: RequestInit) => Promise<Response> = async () => respond({ id: archived.id })) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/stories', component: StoryPage },
    { path: '/stories/:id', component: { template: '<div />' } },
  ] })
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (init.method === 'DELETE') return remove(url, init)
    return respond({ items: url.startsWith('/api/training/stories?') ? [archived, kept] : [], nextCursor: null })
  })
  vi.stubGlobal('fetch', fetcher)
  await router.push('/stories')
  await router.isReady()
  wrapper = mount(StoryPage, { global: { plugins: [router] } })
  await flushPromises()
  return { router, fetcher }
}
function row() { return wrapper!.findAll('article.training-list-row').find(item => item.text().includes('自我介绍'))! }
function removeButton() { return row().get('button') }
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals(); sessionStorage.clear() })

describe('已保存故事删除', () => {
  it('已归档故事也有删除入口，取消后保留故事与列表位置', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(false))
    const { router, fetcher } = await render()
    expect(row().text()).toContain('已归档')
    await removeButton().trigger('click')
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
    expect(row().exists()).toBe(true)
    expect(router.currentRoute.value.path).toBe('/stories')
  })

  it('确认后删除选中故事，保留其他故事和新建表单中尚未保存的内容', async () => {
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    sessionStorage.setItem('v2:story:archived-story', '旧编辑缓存')
    const { router, fetcher } = await render()
    await wrapper!.get('#story-action').setValue('我尚未保存的项目行动')
    expect(removeButton().element.closest('a')).toBeNull()
    await removeButton().trigger('click')
    await flushPromises()
    const [url, init] = fetcher.mock.calls.find(([, init]) => init?.method === 'DELETE')!
    expect(url).toBe('/api/training/stories/archived-story')
    expect(JSON.parse(String(init!.body))).toEqual({ expectedVersion: 3, requestId: expect.any(String) })
    expect(wrapper!.find('a[href="/stories/archived-story"]').exists()).toBe(false)
    expect(wrapper!.find('a[href="/stories/kept-story"]').exists()).toBe(true)
    expect((wrapper!.get('#story-action').element as HTMLTextAreaElement).value).toBe('我尚未保存的项目行动')
    expect(sessionStorage.getItem('v2:story:archived-story')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/stories')
  })

  it('请求处理中禁用删除，阻止重复提交', async () => {
    let finish!: (response: Response) => void
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    const { fetcher } = await render(() => new Promise(resolve => { finish = resolve }))
    await removeButton().trigger('click')
    await removeButton().trigger('click')
    expect(removeButton().attributes('disabled')).toBeDefined()
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
    finish(respond({ id: archived.id }))
    await flushPromises()
    expect(wrapper!.find('a[href="/stories/archived-story"]').exists()).toBe(false)
  })

  it('失败时保留记录，重试成功后清除错误并移除故事', async () => {
    let attempts = 0
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await render(async () => ++attempts === 1 ? respond({ message: '删除失败，请重试', code: 'WRITE_FAILED' }, 500) : respond({ id: archived.id }))
    await removeButton().trigger('click')
    await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toContain('删除失败')
    expect(row().exists()).toBe(true)
    expect(removeButton().attributes('disabled')).toBeUndefined()
    await removeButton().trigger('click')
    await flushPromises()
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper!.find('a[href="/stories/archived-story"]').exists()).toBe(false)
  })
})
