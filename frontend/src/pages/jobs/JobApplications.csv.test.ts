import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import JobApplications from './JobApplications.vue'
const record = { id:'csv-1', company:'公司', position:'岗位', location:'', platform:'', url:'', jd:'', jobType:'OTHER', status:'TO_APPLY', appliedDate:null, nextActionDate:null, interviewAt:null, interviewNote:'', notes:'', archived:false, createdAt:'2026-10-22T00:00:00Z', updatedAt:'2026-10-22T00:00:00Z' }
const respond=(body:unknown,status=200)=>new Response(status===204?null:JSON.stringify(body),{status})
let wrapper:any
afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';vi.unstubAllGlobals()})
it('导入先预览，存在错误时不能确认写入，空结果可导出模板',async()=>{
 let previewed=false, imported=false
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   if(url.startsWith('/api/job-applications?')&&!options?.method)return respond([])
   if(url==='/api/job-applications/csv/template')return new Response('\ufeff格式,记录ID',{status:200,headers:{'Content-Type':'text/csv'}})
   if(url==='/api/job-applications/csv/preview'){previewed=true;return respond({previewToken:'p1',total:2,valid:1,created:1,duplicates:0,conflicts:0,errors:[{line:3,message:'岗位名称不能为空'}]})}
   if(url==='/api/job-applications/csv/import'){imported=true;return respond({created:1,duplicates:0})}
   throw new Error(`未知请求：${url}`)
 }))
 wrapper=mount(JobApplications,{attachTo:document.body});await flushPromises()
 await wrapper.get('[data-job-import]').trigger('click'); const file=new File(['a,b'],'jobs.csv',{type:'text/csv'}); const input=wrapper.get('[data-job-file]').element; Object.defineProperty(input,'files',{value:[file]}); await wrapper.get('[data-job-file]').trigger('change'); await flushPromises()
 expect(previewed).toBe(true);expect(wrapper.text()).toContain('存在 1 行错误');expect(wrapper.find('[data-job-import-confirm]').exists()).toBe(false);expect(imported).toBe(false)
 expect(wrapper.find('[data-job-template]').exists()).toBe(true)
})
it('无错误预览可以确认，成功后刷新列表',async()=>{
 let imported=false
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   if(url.startsWith('/api/job-applications?')&&!options?.method)return respond(imported?[record]:[])
   if(url==='/api/job-applications/csv/preview')return respond({previewToken:'p2',total:1,valid:1,created:1,duplicates:0,conflicts:0,errors:[]})
   if(url==='/api/job-applications/csv/import'){imported=true;return respond({created:1,duplicates:0})}
   throw new Error(`未知请求：${url}`)
 }))
 wrapper=mount(JobApplications,{attachTo:document.body});await flushPromises(); await wrapper.get('[data-job-import]').trigger('click');const input=wrapper.get('[data-job-file]').element;Object.defineProperty(input,'files',{value:[new File(['x'],'x.csv',{type:'text/csv'})]});await wrapper.get('[data-job-file]').trigger('change');await flushPromises();await wrapper.get('[data-job-import-confirm]').trigger('click');await flushPromises();expect(imported).toBe(true);expect(wrapper.text()).toContain('岗位')
})
