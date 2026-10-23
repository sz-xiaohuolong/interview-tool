/**
 * 岗位搜索隔离测试浏览器。所有招聘页面、职位与响应均为合成数据。
 * 只供 e2e-job-search.sh 调用，不接触日常 Chrome，不向招聘站点发送请求。
 */
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const require = createRequire(new URL('../frontend/package.json', import.meta.url))
const { chromium } = require('@playwright/test')
const testRoot = process.argv[2]
if (!testRoot) throw new Error('必须提供隔离测试目录。')
const profile = resolve(testRoot, 'browser-profile')
await mkdir(profile, { recursive: true })
const context = await chromium.launchPersistentContext(profile, {
  headless: true,
  args: ['--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1'],
  serviceWorkers: 'block',
})

const labels = { BOSS: 'BOSS', LIEPIN: '猎聘', ZHAOPIN: '智联' }
const htmlEscape = text => String(text).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
const description = '【合成测试正文】\n岗位职责：负责 Java 服务、交易系统与数据库性能优化。\n任职要求：理解事务、索引、并发与故障恢复；能说明项目中的设计取舍。\n本页面仅用于隔离软件验收，不代表真实招聘信息。'
const records = (source, batch, size) => Array.from({ length: size }, (_, index) => {
  const n = (batch - 1) * size + index + 1
  const id = source === 'ZHAOPIN' ? `CC000000100J${String(n).padStart(11, '0')}` : `fixture${source.toLowerCase()}${String(n).padStart(4, '0')}`
  const url = source === 'BOSS' ? `https://www.zhipin.com/job_detail/${id}.html` : source === 'LIEPIN' ? `https://www.liepin.com/job/${id}.shtml` : `https://www.zhaopin.com/jobdetail/${id}.htm`
  return { id, url, title: `Java 后端工程师 · 合成${labels[source]}${n}`, company: `合成${labels[source]}科技`, location: '深圳·南山·科技园', salary: n === 2 ? '面议' : '20-30K·13薪', experience: n === 2 ? '' : '3-5年', education: n === 2 ? '' : '本科' }
})
const shell = (body, script = '') => `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>合成招聘测试页面</title></head><body>${body}<script>${script}</script></body></html>`
const bossItems = jobs => jobs.map(job => ({ encryptJobId: job.id, jobName: job.title, brandName: job.company, cityName: '深圳', areaDistrict: '南山', businessDistrict: '科技园', salaryDesc: job.salary, jobExperience: job.experience, jobDegree: job.education, jobLabels: [], skills: [] }))
// 使用已在真实页面核实的字段，避免宽松别名掩盖适配器漏读。
const zhaoItems = jobs => jobs.map(job => ({ jobId: Number(job.id.slice(-11)), number: job.id, name: job.title, positionURL: job.url, companyName: job.company, salary60: job.salary, salaryCount: '', workCity: '深圳', cityDistrict: '南山', streetName: '科技园', tradingArea: '', workingExp: job.experience, education: job.education, publishTime: '2026-10-20 09:30:00' }))
function card(source, job) {
  if (source === 'BOSS') return `<li class="job-card-wrapper"><a class="job-name" href="${job.url}">${job.title}</a><span class="company-name">${job.company}</span><span class="salary">\ue001\ue002</span><span class="job-area">${job.location}</span><ul class="tag-list"><li>${job.experience}</li><li>${job.education}</li></ul></li>`
  if (source === 'LIEPIN') return `<div class="jobCardPcContainer"><a class="jobDetailBox" href="${job.url}"><div class="job-title-box"><div class="job-title">${job.title}</div></div><p class="job-salary">${job.salary}</p><p class="company-name">${job.company}</p><p class="job-dq">${job.location}</p><p class="job-edu">${job.education}</p><p class="job-exp">${job.experience}</p></a></div>`
  return `<div class="job-card"><a class="job-card__title" href="${job.url}"><span class="job-card__title-clamp">${job.title}</span></a><span class="job-card__salary">${job.salary}</span><span class="job-card__company-name">${job.company}</span><span class="job-card__location">${job.location}</span><span class="job-card__years">${job.experience}</span><span class="job-card__degree">${job.education}</span><div class="job-card__tags"><span>${job.experience}</span><span>${job.education}</span></div></div>`
}
function listPage(source, url) {
  const keyword = url.searchParams.get('query') || url.searchParams.get('key') || url.searchParams.get('kw') || ''
  if (keyword.includes('全部失败') || keyword.includes('部分失败') && source === 'LIEPIN') return shell('<h1>请完成安全验证</h1><p>合成验证码状态，不进行真实验证。</p>')
  if (keyword.includes('空结果')) return shell('<div class="empty">暂无相关职位，没有搜索结果</div>', source === 'ZHAOPIN' ? 'window.__INITIAL_STATE__={positionList:[],hasMore:false,positionCount:0};' : source === 'BOSS' ? `fetch('/wapi/zpgeek/search/joblist.json?page=1&query='+encodeURIComponent(${JSON.stringify(keyword)}));` : '')
  const batch = Number(url.searchParams.get('currentPage') || '0') + 1
  const size = source === 'BOSS' ? 15 : source === 'LIEPIN' ? 40 : 20
  const jobs = records(source, batch, size)
  if (source === 'LIEPIN') return shell(jobs.map(job => card(source, job)).join('') + `<ul class="ant-pagination"><li class="ant-pagination-item ant-pagination-item-active">${batch}</li><li class="ant-pagination-item ant-pagination-item-${batch + 1}"><a href="/zhaopin/?key=${encodeURIComponent(keyword)}&dq=050090&currentPage=${batch}&pageSize=40">${batch + 1}</a></li><li class="ant-pagination-next"><a href="/zhaopin/?key=${encodeURIComponent(keyword)}&dq=050090&currentPage=${batch}&pageSize=40">下一页</a></li></ul>`)
  const dataUrl = source === 'BOSS' ? '/wapi/zpgeek/search/joblist.json' : '/c/i/search/positions'
  const container = source === 'BOSS' ? '<ul class="rec-job-list" id="cards"></ul>' : '<div id="cards"></div>'
  const initialState = source === 'ZHAOPIN' ? `window.__INITIAL_STATE__={positionList:${JSON.stringify(zhaoItems(jobs))},hasMore:true,positionCount:200,pageIndex:1};` : ''
  // 正常浏览器 fetch 产生可被 CDP 读取的响应；滚动只追加一批。
  return shell(container + '<div style="height:1800px">合成测试分页区域</div>', `${initialState}
    let batch=1,busy=false;const source=${JSON.stringify(source)};
    const snippets=${JSON.stringify(Array.from({ length: 8 }, (_, i) => records(source, i + 1, size).map(job => card(source, job)).join('')))};
    async function load(){if(busy||batch>7)return;busy=true;try{const r=await fetch(${JSON.stringify(dataUrl)}+'?page='+batch+'&query='+encodeURIComponent(${JSON.stringify(keyword)}));const d=await r.json();document.getElementById('cards').insertAdjacentHTML('beforeend',snippets[batch-1]);${source === 'ZHAOPIN' ? 'if(batch>1)window.__INITIAL_STATE__.positionList.push(...d.data.list);window.__INITIAL_STATE__.pageIndex=batch;' : ''}batch++;}finally{busy=false;}}
    addEventListener('scroll',()=>{if(scrollY+innerHeight>=document.documentElement.scrollHeight-120)void load();});void load();`)
}

const requests = []
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url())
  const source = url.hostname === 'www.zhipin.com' ? 'BOSS' : url.hostname === 'www.liepin.com' ? 'LIEPIN' : ['www.zhaopin.com', 'fe-api.zhaopin.com'].includes(url.hostname) ? 'ZHAOPIN' : null
  if (!source) return route.abort('blockedbyclient')
  requests.push({ source, method: request.method(), path: url.pathname, page: url.searchParams.get('page') || url.searchParams.get('currentPage') })
  if (request.method() !== 'GET' && !url.pathname.endsWith('/positions')) return route.abort('blockedbyclient')
  if (url.pathname === '/wapi/zpgeek/search/joblist.json') {
    const empty = (url.searchParams.get('query') || '').includes('空结果')
    const jobs = empty ? [] : bossItems(records('BOSS', Number(url.searchParams.get('page') || '1'), 15))
    return route.fulfill({ json: { code: 0, zpData: { hasMore: !empty, jobList: jobs } } })
  }
  if (url.pathname === '/c/i/search/positions') {
    const jobs = zhaoItems(records('ZHAOPIN', Number(url.searchParams.get('page') || '1'), 20))
    return route.fulfill({ json: { code: 200, data: { list: jobs, results: jobs, hasMore: true, total: 200 } } })
  }
  if (['/web/geek/jobs', '/jobs/', '/zhaopin/'].includes(url.pathname)) {
    if ([...url.searchParams.values()].some(value => value.includes('等待停止'))) await new Promise(resolve => setTimeout(resolve, 3500))
    return route.fulfill({ contentType: 'text/html; charset=utf-8', body: listPage(source, url) })
  }
  if (/^\/(job_detail|job|jobdetail)\//.test(url.pathname)) {
    const selector = source === 'BOSS' ? '<div class="job-detail-body"><div class="desc">' : source === 'LIEPIN' ? '<div data-selector="job-intro-content">' : '<div class="job-description__content">'
    const restricted = source === 'BOSS' && url.pathname.endsWith('/fixtureboss0015.html')
    const body = selector + htmlEscape(restricted ? '【合成截断正文】完整要求需登录查看。' : description) + (source === 'BOSS' ? '</div></div>' : '</div>')
    return route.fulfill({ contentType: 'text/html; charset=utf-8', body: shell(body + (restricted ? '<p>登录查看完整内容</p>' : '')) })
  }
  return route.abort('blockedbyclient')
})

await writeFile(resolve(testRoot, 'fixture-ready'), '合成 CDP 浏览器已就绪\n')
let closing = false
async function stop() {
  if (closing) return
  closing = true
  await writeFile(resolve(testRoot, 'fixture-requests.json'), JSON.stringify(requests, null, 2))
  await context.close()
}
process.on('SIGTERM', () => void stop())
process.on('SIGINT', () => void stop())
console.log('合成 CDP 浏览器已启动；所有招聘请求均由隔离替身响应。')
