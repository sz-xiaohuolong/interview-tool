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
const kept = {
  ...archived, id: 'kept-story', templateKind: 'STAR', status: 'DRAFT', revision: 0,
  fields: {
    situation: { text: '系统高并发掉单', originType: 'USER_ASSERTION' },
    task: { text: '重构分布式锁', originType: 'USER_ASSERTION' },
    action: { text: '使用 Redlock 幂等防重', originType: 'USER_ASSERTION' },
    result: { text: '掉单率归零', originType: 'USER_ASSERTION' },
    reflection: { text: '后续引入队列削峰', originType: 'USER_ASSERTION' },
  },
  currentVersionDetail: { expressionDraft: '我在项目中负责解决高并发掉单问题…' },
}
const respond = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
let wrapper: VueWrapper | undefined
async function render(remove: (url: string, init: RequestInit) => Promise<Response> = async () => respond({ id: archived.id })) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/stories', component: StoryPage },
    { path: '/stories/:id', component: StoryPage },
  ] })
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (init.method === 'DELETE') return remove(url, init)
    if (url.includes('/available-materials')) {
      return respond({ items: [{ id: 'mat-1', name: '我的简历.pdf', kind: 'RESUME' }], nextCursor: null })
    }
    if (url === '/api/training/stories/kept-story') {
      return respond(kept)
    }
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
function removeButton() { return row().get('button.story-delete') }
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

describe('AI 一键提炼与全量确认流程', () => {
  it('新建态下勾选材料点击一键提炼，自动链式调用创建草稿 -> 提取材料 -> 生成模型草稿', async () => {
    const { router, fetcher } = await render()
    
    // 模拟新建草稿返回、准备返回和生成返回
    fetcher.mockImplementation(async (url: string, init: RequestInit = {}) => {
      if (url.includes('/available-materials')) {
        return respond({ items: [{ id: 'mat-1', name: '我的简历.pdf', kind: 'RESUME' }], nextCursor: null })
      }
      if (url === '/api/training/stories' && init.method === 'POST') {
        return respond({ id: 'new-story-123', revision: 1, currentVersion: 1, status: 'DRAFT', fields: kept.fields })
      }
      if (url === '/api/training/stories/new-story-123/input-preparations' && init.method === 'POST') {
        return respond({ manifestId: 'mani-1', manifestHash: 'hash-1' })
      }
      if (url === '/api/training/stories/new-story-123/draft-generation' && init.method === 'POST') {
        return respond({ id: 'new-story-123', revision: 2, currentVersion: 2, status: 'DRAFT', fields: kept.fields, currentVersionDetail: { expressionDraft: 'AI生成的表达稿' } })
      }
      if (url === '/api/training/stories/new-story-123') {
        return respond({ id: 'new-story-123', revision: 2, currentVersion: 2, status: 'DRAFT', fields: kept.fields, currentVersionDetail: { expressionDraft: 'AI生成的表达稿' } })
      }
      return respond({ items: [], nextCursor: null })
    })

    // 勾选材料
    const checkbox = wrapper!.find('input[type="checkbox"][value="mat-1"]')
    if (checkbox.exists()) {
      await checkbox.setValue(true)
      await flushPromises()
    }

    // 找到 AI 一键提炼按钮并点击
    const magicBtn = wrapper!.find('button.btn-magic')
    expect(magicBtn.exists()).toBe(true)
    await magicBtn.trigger('click')
    await flushPromises()

    // 断言链式调用发生
    const calledUrls = fetcher.mock.calls.map(([url]) => url)
    expect(calledUrls).toContain('/api/training/stories')
    expect(calledUrls).toContain('/api/training/stories/new-story-123/input-preparations')
    expect(calledUrls).toContain('/api/training/stories/new-story-123/draft-generation')
    expect(router.currentRoute.value.path).toBe('/stories/new-story-123')
  })

  it('已有故事草稿时支持一键确认全部内容（本人声明）', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/stories', component: StoryPage },
      { path: '/stories/:id', component: StoryPage },
    ] })
    const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
      if (url.includes('/available-materials')) {
        return respond({ items: [], nextCursor: null })
      }
      if (url === '/api/training/stories/kept-story') {
        return respond(kept)
      }
      if (url === '/api/training/stories/kept-story/confirm' && init.method === 'POST') {
        return respond({ ...kept, status: 'CONFIRMED' })
      }
      return respond({ items: [], nextCursor: null })
    })
    vi.stubGlobal('fetch', fetcher)
    await router.push('/stories/kept-story')
    await router.isReady()
    const storyWrapper = mount(StoryPage, { global: { plugins: [router] } })
    await flushPromises()

    // 查找一键确认全部内容按钮
    const confirmBtn = storyWrapper.findAll('button').find(b => b.text().includes('确认全部内容'))
    expect(confirmBtn?.exists()).toBe(true)
    await confirmBtn!.trigger('click')
    await flushPromises()

    // 确认调用了 confirm API
    const confirmCall = fetcher.mock.calls.find(([url]) => url === '/api/training/stories/kept-story/confirm')
    expect(confirmCall).toBeDefined()
    const body = JSON.parse(String(confirmCall![1]?.body))
    expect(body.confirmations.action.confirmed).toBe(true)
    storyWrapper.unmount()
  })
})

async function editStory(path = '/stories', initial: any = kept, rejectSave = false, generationState = 'READY') {
  let current = structuredClone(initial)
  const writes: { path: string; body: any }[] = []
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/stories', component: StoryPage }, { path: '/stories/:id', component: StoryPage },
  ] })
  vi.stubGlobal('fetch', async (url: string, init: RequestInit = {}) => {
    const u = new URL(url, 'http://localhost'), method = init.method || 'GET'
    const body = init.body ? JSON.parse(String(init.body)) : null
    if (method !== 'GET') writes.push({ path: u.pathname, body })
    if (method === 'GET' && u.pathname === '/api/training/available-materials') return respond({ items: [{ id: 'mat-1', name: '合成资料', kind: 'PROJECT' }], nextCursor: null })
    if (method === 'GET' && u.pathname === '/api/training/stories') return respond({ items: [], nextCursor: null })
    if (method === 'GET' && u.pathname === '/api/training/stories/kept-story') return respond(current)
    if (method === 'POST' && u.pathname === '/api/training/stories') {
      if (rejectSave) return respond({ message: '暂时无法保存' }, 503)
      current = { ...current, templateKind: body.templateKind, fields: body.fields, currentVersionDetail: { expressionDraft: body.expressionDraft } }
      return respond(current)
    }
    if (method === 'POST' && u.pathname === '/api/training/stories/kept-story/versions') {
      current = { ...current, status: 'DRAFT', revision: current.revision + 1, currentVersion: current.currentVersion + 1, fields: body.fields, currentVersionDetail: { expressionDraft: body.expressionDraft } }
      return respond(current)
    }
    if (method === 'POST' && u.pathname === '/api/training/stories/kept-story/input-preparations') return respond({ manifestId: 'synthetic', manifestHash: 'synthetic-hash' })
    if (method === 'POST' && u.pathname === '/api/training/stories/kept-story/draft-generation') { current = { ...current, generationState }; return respond(current) }
    if (method === 'POST' && u.pathname === '/api/training/stories/kept-story/confirm') { current = { ...current, status: 'CONFIRMED' }; return respond(current) }
    throw new Error(`未约定请求：${method} ${url}`)
  })
  await router.push(path); await router.isReady()
  wrapper = mount({ template: '<router-view :key="$route.fullPath" />' }, { global: { plugins: [router] } })
  await flushPromises()
  return { router, writes }
}
const kindButton = (text: string) => wrapper!.findAll('button').find(b => b.text() === text)!
const textValue = (id: string) => (wrapper!.get(id).element as HTMLTextAreaElement).value

it('重复点击当前模板不清空正文、表达稿和所选资料', async () => {
  await editStory()
  await wrapper!.get('#story-action').setValue('我的未保存行动')
  await wrapper!.get('#story-expression').setValue('我的表达稿')
  await wrapper!.get('input[value="mat-1"]').setValue(true)
  await kindButton('STAR 项目故事').trigger('click')
  expect(textValue('#story-action')).toBe('我的未保存行动')
  expect(textValue('#story-expression')).toBe('我的表达稿')
  expect((wrapper!.get('input[value="mat-1"]').element as HTMLInputElement).checked).toBe(true)
})
it('两种模板分别保留正文、表达稿和资料，刷新恢复上次模板', async () => {
  await editStory()
  await wrapper!.get('#story-action').setValue('STAR未保存行动')
  await wrapper!.get('#story-expression').setValue('STAR表达稿')
  await wrapper!.get('input[value="mat-1"]').setValue(true)
  await kindButton('自我介绍').trigger('click')
  await wrapper!.get('#story-contribution').setValue('自我介绍的个人贡献')
  await wrapper!.get('#story-expression').setValue('自我介绍表达稿')
  expect((wrapper!.get('input[value="mat-1"]').element as HTMLInputElement).checked).toBe(false)
  await kindButton('STAR 项目故事').trigger('click')
  expect(textValue('#story-action')).toBe('STAR未保存行动')
  expect(textValue('#story-expression')).toBe('STAR表达稿')
  expect((wrapper!.get('input[value="mat-1"]').element as HTMLInputElement).checked).toBe(true)
  await kindButton('自我介绍').trigger('click')
  wrapper!.unmount()
  await editStory()
  expect(wrapper!.find('#story-contribution').exists()).toBe(true)
  expect(textValue('#story-contribution')).toBe('自我介绍的个人贡献')
  expect(textValue('#story-expression')).toBe('自我介绍表达稿')
})
it('保存成功只清除当前新建模板草稿，其他模板仍可恢复', async () => {
  await editStory()
  await wrapper!.get('#story-action').setValue('另一份STAR行动')
  await kindButton('自我介绍').trigger('click')
  await wrapper!.get('#story-contribution').setValue('已保存的INTRO贡献')
  await kindButton('保存草稿').trigger('click'); await flushPromises()
  wrapper!.unmount(); await editStory()
  expect(textValue('#story-contribution')).toBe('')
  await kindButton('STAR 项目故事').trigger('click')
  expect(textValue('#story-action')).toBe('另一份STAR行动')
})
it('保存失败后刷新仍能恢复新建草稿', async () => {
  await editStory('/stories', kept, true)
  await wrapper!.get('#story-action').setValue('失败后不能丢的行动')
  await kindButton('保存草稿').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('暂时无法保存')
  wrapper!.unmount(); await editStory()
  expect(textValue('#story-action')).toBe('失败后不能丢的行动')
})
it.each(['保存为新版本', '确认全部内容（本人声明）'])('引用文字编辑后%s自动作为用户编辑提交，未修改的引用保留', async button => {
  const quoted = structuredClone(kept)
  quoted.fields.action.originType = 'MATERIAL_QUOTE'
  quoted.fields.task.originType = 'MATERIAL_QUOTE'
  const { writes } = await editStory('/stories/kept-story', quoted)
  await wrapper!.get('#story-action').setValue('我补充了超时后的处理边界')
  await kindButton(button).trigger('click'); await flushPromises()
  const save = writes.find(w => w.path.endsWith('/versions'))!
  expect(save.body.fields.action).toEqual({ text: '我补充了超时后的处理边界', originType: 'USER_ASSERTION' })
  expect(save.body.fields.task.originType).toBe('MATERIAL_QUOTE')
  expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
})

it.each(['READY', 'FAILED'])('新建生成结果为%s时只按真实成功状态清理草稿', async state => {
  const { router } = await editStory('/stories', kept, false, state)
  await wrapper!.get('#story-action').setValue('生成前输入的个人行动')
  await wrapper!.get('input[value="mat-1"]').setValue(true)
  await wrapper!.get('button.btn-magic').trigger('click'); await flushPromises()
  await router.push('/stories'); await flushPromises()
  expect(textValue('#story-action')).toBe(state === 'READY' ? '' : '生成前输入的个人行动')
})
