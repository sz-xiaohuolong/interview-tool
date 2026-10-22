import { afterEach, describe, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Materials from './Materials.vue'
import type { Material } from '../../types'

const timestamp = '2026-10-15T04:00:00Z'
const resume: Material = {
  id: 'resume-1', kind: 'RESUME', name: '我的简历', content: '', createdAt: timestamp, updatedAt: timestamp,
  documents: [{ id: 'resume-document', name: '简历.md', text: '简历开头\n完整的项目经历\n简历末尾', truncated: true, createdAt: timestamp }],
}
const project: Material = {
  id: 'project-1', kind: 'PROJECT', name: '知识库项目', content: '项目简介：企业知识库\n技术栈：Java\n职责：检索服务', createdAt: timestamp, updatedAt: timestamp,
  documents: [{ id: 'project-document', name: '架构.md', text: '附件独立内容，不得写入手工正文', truncated: false, createdAt: timestamp }],
}
let component: VueWrapper | undefined
let wrapper: DOMWrapper<HTMLElement> | undefined
const respond = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status })

async function render(path = "/materials") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/materials', component: Materials },
    { path: '/configure/:mode', component: { template: '<div />' } },
  ] })
  await router.push(path)
  await router.isReady()
  component = mount(Materials, { attachTo: document.body, global: { plugins: [router] } })
  wrapper = new DOMWrapper(document.body)
  await flushPromises()
  return wrapper
}

function button(text: string) {
  const result = wrapper!.findAll('button').find(item => item.text().trim() === text)
  expect(result, `应显示“${text}”按钮`).toBeDefined()
  return result!
}

async function selectFiles(selector: string, files: File[]) {
  const input = wrapper!.get(selector)
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
}

afterEach(() => {
  component?.unmount()
  component = undefined
  wrapper = undefined
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('资料库页面（仅替换外部 HTTP）', () => {
  it('项目入口链接直接打开项目标签而不是简历', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond([resume, project])))
    await render('/materials?kind=PROJECT')
    expect(button('新增资料')).toBeDefined()
    expect(wrapper!.text()).toContain('知识库项目')
    expect(wrapper!.text()).not.toContain('我的简历')
  })

  it('默认全部按更新时间排序，卡片只有摘录，点击才打开详情并保留分类搜索', async () => {
    const second = { ...resume, id: 'resume-2', name: '第二份简历', content: '独立正文', documents: [], updatedAt: '2026-10-21T04:00:00Z' }
    const fetcher = vi.fn().mockImplementation(async (url, options) => {
      if (url === '/api/materials' && !options?.method) return respond([resume, project, second])
      throw new Error(`未知请求：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    await render()
    expect(wrapper!.get('[data-kind="ALL"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper!.findAll('[data-material-preview]').map(node => node.attributes('data-material-preview'))).toEqual(['resume-2', 'project-1', 'resume-1'])
    expect(wrapper!.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper!.find('[aria-label="资料内容预览"]').exists()).toBe(false)
    await wrapper!.get('[data-kind="RESUME"]').trigger('click')
    await wrapper!.get('#materials-search').setValue('第二份')
    await wrapper!.get('[data-material-preview="resume-2"]').trigger('click')
    await flushPromises()
    expect(wrapper!.get('[aria-label="资料内容预览"]').text()).toContain('独立正文')
    await button('关闭').trigger('click')
    await flushPromises()
    expect(wrapper!.get('[data-kind="RESUME"]').attributes('aria-pressed')).toBe('true')
    expect((wrapper!.get('#materials-search').element as HTMLInputElement).value).toBe('第二份')
    expect(wrapper!.findAll('[data-material-preview]')).toHaveLength(1)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('搜索只匹配名称或手工正文并忽略大小写和首尾空格，附件不参与', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond([resume, project])))
    await render('/materials?kind=unknown')
    await wrapper!.get('#materials-search').setValue('  JAVA  ')
    expect(wrapper!.findAll('[data-material-preview]')).toHaveLength(1)
    expect(wrapper!.get('[role="status"]').text()).toContain('匹配 1 份资料')
    expect(wrapper!.text()).toContain(project.name)
    await wrapper!.get('#materials-search').setValue('附件独立内容')
    expect(wrapper!.findAll('[data-material-preview]')).toHaveLength(0)
    expect(wrapper!.get('[role="status"]').text()).toContain('匹配 0 份资料')
    expect(wrapper!.text()).toContain('没有匹配的资料')
    await button('清空搜索').trigger('click')
    expect(wrapper!.findAll('[data-material-preview]')).toHaveLength(2)
  })

  it('拖入简历只进入待上传，多文件明确拒绝且不发送请求', async () => {
    const fetcher = vi.fn().mockResolvedValue(respond([]))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await button('新增资料').trigger('click')
    await button('新增简历').trigger('click')
    await flushPromises()
    const file = new File(['合成资料'], '候选人.md')
    await wrapper!.get('[data-dropzone]').trigger('drop', { dataTransfer: { files: [file] } })
    expect(wrapper!.get('[role="dialog"]').text()).toContain('候选人.md')
    expect(fetcher).toHaveBeenCalledTimes(1)
    await wrapper!.get('[data-dropzone]').trigger('drop', { dataTransfer: { files: [file, file] } })
    expect(wrapper!.get('[role="alert"]').text()).toContain('一份')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('从详情编辑时只显示一个浮层，取消回到原详情', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond([project])))
    await render()
    await button('查看').trigger('click')
    await flushPromises()
    await button('编辑正文').trigger('click')
    await flushPromises()
    expect(wrapper!.findAll('[role="dialog"]')).toHaveLength(1)
    await button('取消').trigger('click')
    await flushPromises()
    expect(wrapper!.findAll('[role="dialog"]')).toHaveLength(1)
    expect(wrapper!.get('[aria-label="资料内容预览"]').text()).toContain(project.content)
  })

  it('编辑后若被原搜索隐藏，则切换资料类型并清空搜索展示保存结果', async () => {
    const fetcher = vi.fn().mockImplementation(async (url, options) => {
      if (url === '/api/materials' && !options?.method) return respond([project])
      if (url === '/api/materials/project-1' && options?.method === 'PUT') return respond({ ...project, ...JSON.parse(options.body) })
      throw new Error(`未知请求：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    await render()
    await wrapper!.get('#materials-search').setValue('知识库项目')
    await button('编辑').trigger('click')
    await flushPromises()
    await wrapper!.get('#material-name').setValue('新的项目名称')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    await button('关闭').trigger('click')
    await flushPromises()
    expect(wrapper!.get('[data-kind="PROJECT"]').attributes('aria-pressed')).toBe('true')
    expect((wrapper!.get('#materials-search').element as HTMLInputElement).value).toBe('')
    expect(wrapper!.text()).toContain('新的项目名称')
  })

  it('已有20个附件时拒绝继续添加，文件验证失败不会写入', async () => {
    const full = { ...project, documents: Array.from({ length: 20 }, (_, i) => ({ ...project.documents[0]!, id: `d${i}` })) }
    const fetcher = vi.fn().mockResolvedValue(respond([full]))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await button('查看').trigger('click')
    await flushPromises()
    expect(wrapper!.get('#document-files').attributes('disabled')).toBeDefined()
    await wrapper!.get('[data-dropzone]').trigger('drop', { dataTransfer: { files: [new File(['正文'], '超额.md')] } })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper!.text()).not.toContain('待上传 1')
  })

  it('初始读取完成前禁用新增，避免延迟列表覆盖刚保存的资料', async () => {
    let resolveLoad!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(resolve => { resolveLoad = resolve })))
    await render()
    expect(button('新增资料').attributes('disabled')).toBeDefined()
    resolveLoad(respond([]))
    await flushPromises()
    expect(button('新增资料').attributes('disabled')).toBeUndefined()
  })

  it('显示上传简历的附件全文及截断提示，并传递资料 ID 到配置页', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond([resume, project])))
    await render()
    expect(wrapper!.text()).toContain('我的简历')
    expect(wrapper!.find('a[href="/configure/resume?materialId=resume-1"]').exists()).toBe(false)
    await wrapper!.get('[data-material-preview="resume-1"]').trigger('click')
    await flushPromises()
    expect(wrapper!.get('[role="dialog"]').text()).toContain('简历开头\n完整的项目经历\n简历末尾')
    expect(wrapper!.get('[role="dialog"]').text()).toContain('截断')
  })

  it('编辑项目只提交手工正文，保留独立的附件内容', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([project])).mockImplementation(async (_url, options) => {
      const body = JSON.parse(options.body)
      return respond({ ...project, name: body.name, content: body.content })
    })
    vi.stubGlobal('fetch', fetcher)
    await render()
    await wrapper!.get('[data-kind="PROJECT"]').trigger('click')
    await button('编辑').trigger('click')
    await flushPromises()
    expect((wrapper!.get('#material-content').element as HTMLTextAreaElement).value).toBe(project.content)
    await wrapper!.get('#material-content').setValue('更新后的手工职责')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(fetcher).toHaveBeenLastCalledWith('/api/materials/project-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: '知识库项目', content: '更新后的手工职责' }) }))
    expect(wrapper!.get('[role="dialog"]').text()).toContain('更新后的手工职责')
    expect(wrapper!.get('[role="dialog"]').text()).toContain('附件独立内容，不得写入手工正文')
  })

  it('项目可保存仓库链接并展示读取失败，编辑失败后保留草稿', async () => {
    const fetcher = vi.fn().mockImplementation(async (url, options) => {
      if (url === '/api/materials' && !options?.method) return respond([project])
      if (url === '/api/materials/project-1' && options.method === 'PUT') {
        const body = JSON.parse(options.body)
        return respond({ ...project, repository: { repository: 'acme/orders', url: body.githubUrl, status: 'FAILED', error: 'GitHub 限流' } })
      }
      throw new Error(`未知请求：${url}`)
    })
    vi.stubGlobal('fetch', fetcher)
    await render('/materials?kind=PROJECT')
    await button('编辑').trigger('click')
    await flushPromises()
    await wrapper!.get('#material-github').setValue('https://github.com/acme/orders')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(fetcher).toHaveBeenLastCalledWith('/api/materials/project-1', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: project.name, content: project.content, githubUrl: 'https://github.com/acme/orders' }) }))
    expect(wrapper!.get('[role="dialog"]').text()).toContain('GitHub 限流')
  })

  it('上传简历使用 multipart，保存后展示正文为空的解析结果', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([])).mockResolvedValueOnce(respond(resume))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await button('新增资料').trigger('click')
    await button('新增简历').trigger('click')
    await flushPromises()
    const file = new File(['简历正文'], '简历.md', { type: 'text/markdown' })
    await selectFiles('#resume-file', [file])
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    const [url, options] = fetcher.mock.calls[1]!
    expect(url).toBe('/api/materials/upload')
    expect(options.method).toBe('POST')
    expect(options.body.get('kind')).toBe('RESUME')
    expect(options.body.get('file')).toBe(file)
    expect(wrapper!.get('[role="dialog"]').text()).toContain('简历末尾')
  })

  it('项目多附件部分失败时保留成功结果，重试不重复上传成功文件', async () => {
    const first = { id: 'first', name: '设计.md', text: '设计全文', truncated: false, createdAt: timestamp }
    const second = { id: 'second', name: '补充.txt', text: '补充全文', truncated: false, createdAt: timestamp }
    const fetcher = vi.fn().mockResolvedValueOnce(respond([{ ...project, documents: [] }]))
      .mockResolvedValueOnce(respond({ ...project, documents: [first] }))
      .mockResolvedValueOnce(respond({ message: '文件解析失败', code: 'BAD_REQUEST' }, 400))
      .mockResolvedValueOnce(respond({ ...project, documents: [first, second] }))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await wrapper!.get('[data-kind="PROJECT"]').trigger('click')
    await button('查看').trigger('click')
    await flushPromises()
    await selectFiles('#document-files', [new File(['设计全文'], '设计.md'), new File(['补充全文'], '补充.txt')])
    await button('上传附件').trigger('click')
    await flushPromises()
    expect(wrapper!.get('[role="dialog"]').text()).toContain('设计全文')
    expect(wrapper!.get('[role="alert"]').text()).toContain('文件解析失败')
    await button('上传附件').trigger('click')
    await flushPromises()
    expect(fetcher.mock.calls.slice(1).map(call => (call[1]!.body as FormData).get('file'))).toEqual([
      expect.objectContaining({ name: '设计.md' }), expect.objectContaining({ name: '补充.txt' }), expect.objectContaining({ name: '补充.txt' }),
    ])
    expect(wrapper!.get('[role="dialog"]').text()).toContain('补充全文')
  })

  it('保存请求进行时阻止重复提交，失败后保留填写内容', async () => {
    let resolveSave!: (response: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(respond([])).mockImplementation(() => new Promise(resolve => { resolveSave = resolve }))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await wrapper!.get('[data-kind="JD"]').trigger('click')
    await button('新增资料').trigger('click')
    await button('新增目标 JD').trigger('click')
    await flushPromises()
    await wrapper!.get('#material-name').setValue('Java 后端岗位')
    await wrapper!.get('#material-content').setValue('岗位要求：Java 与数据库')
    await wrapper!.get('form').trigger('submit')
    await wrapper!.get('form').trigger('submit')
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(wrapper!.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolveSave(respond({ message: '暂时无法保存', code: 'WRITE_FAILED' }, 500))
    await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toContain('暂时无法保存')
    expect((wrapper!.get('#material-content').element as HTMLTextAreaElement).value).toBe('岗位要求：Java 与数据库')
  })

  it('删除前确认，取消不发送请求，确认后从列表移除', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([resume])).mockResolvedValueOnce(respond(null, 204))
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', confirm)
    await render()
    await button('删除').trigger('click')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper!.text()).toContain('我的简历')
    await button('删除').trigger('click')
    await flushPromises()
    expect(fetcher).toHaveBeenLastCalledWith('/api/materials/resume-1', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper!.text()).not.toContain('我的简历')
  })

  it('删除附件只移除该文档，保留项目正文与面试入口', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([project])).mockResolvedValueOnce(respond(null, 204))
    vi.stubGlobal('fetch', fetcher)
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true))
    await render()
    await wrapper!.get('[data-kind="PROJECT"]').trigger('click')
    await button('查看').trigger('click')
    await flushPromises()
    await button('删除附件').trigger('click')
    await flushPromises()
    expect(fetcher).toHaveBeenLastCalledWith('/api/materials/project-1/documents/project-document', expect.objectContaining({ method: 'DELETE' }))
    expect(wrapper!.get('[role="dialog"]').text()).not.toContain('附件独立内容')
    expect(wrapper!.get('[role="dialog"]').text()).toContain('职责：检索服务')
    expect(wrapper!.find('a[href="/configure/project?materialId=project-1"]').exists()).toBe(true)
  })

  it('简历拒绝 TXT 文件并保留明确错误，不向服务器发送上传请求', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(respond([]))
    vi.stubGlobal('fetch', fetcher)
    await render()
    await button('新增资料').trigger('click')
    await button('新增简历').trigger('click')
    await flushPromises()
    await selectFiles('#resume-file', [new File(['正文'], '简历.txt')])
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper!.get('[role="alert"]').text()).toContain('格式不支持')
  })

  it('加载失败可重试，目标 JD 不提供开始面试入口', async () => {
    const jd = { ...project, id: 'jd-1', kind: 'JD', name: '目标岗位', documents: [] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(respond({ message: '读取失败', code: 'READ_FAILED' }, 500)).mockResolvedValueOnce(respond([jd])))
    await render()
    expect(wrapper!.get('[role="alert"]').text()).toContain('读取失败')
    await button('重新加载').trigger('click')
    await flushPromises()
    await wrapper!.get('[data-kind="JD"]').trigger('click')
    expect(wrapper!.text()).toContain('目标岗位')
    expect(wrapper!.findAll('a[href^="/configure"]')).toHaveLength(0)
  })
})
