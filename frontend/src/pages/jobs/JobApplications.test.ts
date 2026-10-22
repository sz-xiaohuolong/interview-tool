import { afterEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import JobApplications from './JobApplications.vue'

const first = { id: 'job-one', company: '合成公司', position: 'Java 工程师', location: '上海', platform: '公司官网', url: 'https://example.com/job/1', jd: '正文\n第二行', jobType: 'FULL_TIME', status: 'TO_APPLY', appliedDate: '2026-10-20', nextActionDate: '2026-10-25', interviewAt: '2026-10-24T02:30:00Z', interviewNote: '技术面', notes: '需要准备案例', archived: false, createdAt: '2026-10-22T00:00:00Z', updatedAt: '2026-10-22T00:00:00Z' }
const respond = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status })
let component: VueWrapper
let body: DOMWrapper<HTMLElement>
async function render() { component = mount(JobApplications, { attachTo: document.body }); body = new DOMWrapper(document.body); await flushPromises() }
function button(text: string) { const result = body.findAll('button').find(b => b.text().trim() === text); expect(result, `应有${text}按钮`).toBeDefined(); return result! }
afterEach(() => { component?.unmount(); document.body.innerHTML = ''; vi.unstubAllGlobals() })

it('空列表能最少录入新岗位，保存失败保留输入，成功才关闭', async () => {
  let saveCount = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) return respond([])
    if (url === '/api/job-applications' && options?.method === 'POST') {
      const sent = JSON.parse(options.body as string)
      expect(sent.company).toBe('新增公司'); expect(sent.position).toBe('后端开发'); expect(sent.status).toBe('TO_APPLY')
      if (++saveCount === 1) return respond({ message: '磁盘写入失败' }, 500)
      return respond({ ...first, ...sent })
    }
    throw new Error(`未知请求：${url} ${options?.method}`)
  }))
  await render()
  expect(body.text()).toContain('还没有岗位记录')
  await button('新增岗位').trigger('click'); await flushPromises()
  await body.get('#job-company').setValue('新增公司'); await body.get('#job-position').setValue('后端开发')
  await body.get('#job-form').trigger('submit'); await flushPromises()
  expect(body.get('[role="dialog"]').text()).toContain('磁盘写入失败')
  expect((body.get('#job-company').element as HTMLInputElement).value).toBe('新增公司')
  await body.get('#job-form').trigger('submit'); await flushPromises()
  expect(body.find('[role="dialog"]').exists()).toBe(false)
})

it('公司搜索、状态、归档与排序传递准确的接口条件', async () => {
  const calls: URL[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) { calls.push(new URL(url, 'http://local')); return respond([first]) }
    throw new Error(`未知请求：${url}`)
  }))
  await render()
  await body.get('#job-search').setValue('合成%'); await body.get('#job-filter-status').setValue('INTERVIEW')
  await button('已归档').trigger('click'); await body.get('#job-sort').setValue('appliedDate:asc'); await flushPromises()
  const query = calls.at(-1)!.searchParams
  expect(Object.fromEntries(query)).toEqual({ company: '合成%', status: 'INTERVIEW', archived: 'true', sort: 'appliedDate', direction: 'asc' })
})

it('表格快速修改状态只发 PATCH，失败仍保持原状态', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) return respond([first])
    if (url === '/api/job-applications/job-one/status' && options?.method === 'PATCH') {
      expect(JSON.parse(options.body as string)).toEqual({ status: 'OFFER' }); return respond({ message: '未能保存状态' }, 500)
    }
    throw new Error(`未知请求：${url} ${options?.method}`)
  }))
  await render(); await body.get('[data-job-status]').setValue('OFFER'); await flushPromises()
  expect(body.text()).toContain('未能保存状态')
  expect((body.get('[data-job-status]').element as HTMLSelectElement).value).toBe('TO_APPLY')
})

it('完整详情是可读文本，编辑保持原字段和时间，不抓取原链接', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) return respond([first])
    if (url === '/api/job-applications/job-one' && options?.method === 'PUT') {
      const sent = JSON.parse(options.body as string)
      expect(sent.interviewAt).toBe(first.interviewAt); expect(sent.jd).toBe(first.jd); expect(sent.notes).toBe('改过的备注')
      return respond({ ...first, ...sent })
    }
    throw new Error(`未知请求：${url} ${options?.method}`)
  }))
  await render(); await body.get('[data-job-detail]').trigger('click'); await flushPromises()
  expect(body.get('[role="dialog"]').text()).toContain('需要准备案例')
  expect(body.get('[role="dialog"] a').attributes('href')).toBe(first.url)
  expect(body.get('[role="dialog"] a').attributes('rel')).toContain('noopener')
  await button('编辑岗位').trigger('click'); await flushPromises()
  expect((body.get('#job-location').element as HTMLInputElement).value).toBe('上海')
  await body.get('#job-notes').setValue('改过的备注'); await body.get('#job-form').trigger('submit'); await flushPromises()
  expect(body.find('#job-form').exists()).toBe(false)
})

it('删除必须确认，取消不发送，确认只删除目标记录', async () => {
  let removed = false
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) return respond(removed ? [] : [first])
    if (url === '/api/job-applications/job-one' && options?.method === 'DELETE') { removed = true; return respond(null, 204) }
    throw new Error(`未知请求：${url} ${options?.method}`)
  }))
  await render(); await button('删除').trigger('click'); await flushPromises()
  expect(removed).toBe(false); expect(body.get('[role="dialog"]').text()).toContain('合成公司')
  await button('取消').trigger('click'); await flushPromises(); expect(removed).toBe(false)
  await button('删除').trigger('click'); await flushPromises(); await button('确认删除').trigger('click'); await flushPromises()
  expect(removed).toBe(true); expect(body.text()).toContain('还没有岗位记录')
})

it('列表失败可以重试，不能伪装为空记录', async () => {
  let fail = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) return fail ? respond({ message: '读取失败' }, 503) : respond([first])
    throw new Error(`未知请求：${url}`)
  }))
  await render(); expect(body.text()).toContain('读取失败'); expect(body.text()).not.toContain('还没有岗位记录')
  fail = false; await button('重新加载').trigger('click'); await flushPromises(); expect(body.text()).toContain('Java 工程师')
})

it('过期列表响应不能覆盖最新筛选结果，加载时有明确提示', async () => {
  let resolveOld!: (value: Response) => void
  vi.stubGlobal('fetch', vi.fn((url: string, options?: RequestInit) => {
    if (url.startsWith('/api/job-applications?') && !options?.method) {
      const query = new URL(url, 'http://local').searchParams
      if (query.get('company') === '旧') return new Promise<Response>(resolve => { resolveOld = resolve })
      return Promise.resolve(respond(query.get('company') === '新' ? [{ ...first, company: '新公司' }] : []))
    }
    throw new Error(`未知请求：${url}`)
  }))
  await render(); await body.get('#job-search').setValue('旧'); await flushPromises()
  expect(body.text()).toContain('正在读取岗位记录')
  await body.get('#job-search').setValue('新'); await flushPromises()
  expect(body.text()).toContain('新公司')
  resolveOld(respond([{ ...first, company: '旧公司' }])); await flushPromises()
  expect(body.text()).toContain('新公司'); expect(body.text()).not.toContain('旧公司')
})
