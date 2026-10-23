import { expect, it } from 'vitest'
import { deduplicateJobs, groupJobs, matchesJob, originalOptions, defaultFilters } from './job-search-model'
import type { JobRecord } from '../../api/job-search'
const job = (source = 'BOSS', changes: Partial<JobRecord> = {}): JobRecord => ({ key: `${source}:same`, source, sourceId: 'same', title: '工程师', company: '公司', city: '深圳', location: '深圳 南山', url: 'https://example.com/1', salaryText: '15-25k', salaryMinK: 15, salaryMaxK: 25, salaryMonths: 13, experienceText: '3–10年', educationText: '本科', description: '完整正文\n要求', fetchedAt: '2026-10-23T00:00:00Z', ...changes } as JobRecord)
it('来源身份独立，完整四项一致才折叠，缺字段和近似岗位不折叠', () => {
  const boss = job(), liepin = job('LIEPIN', { description: '完整正文 要求' })
  expect(deduplicateJobs([boss, liepin, boss])).toHaveLength(2)
  expect(groupJobs([boss, liepin])).toHaveLength(1)
  expect(groupJobs([boss, job('LIEPIN', { description: null })])).toHaveLength(2)
  expect(groupJobs([boss, job('LIEPIN', { location: '深圳 福田' })])).toHaveLength(2)
  expect(groupJobs([boss, job('LIEPIN', { title: '高级工程师' })])).toHaveLength(2)
})
it('筛选取月薪交集且同来源满足全部条件，逐字段未知不能抵消不符项', () => {
  const filters = { ...defaultFilters(), minK: 20, maxK: 30, education: '本科' }
  expect(matchesJob(job(), filters)).toBe(true)
  expect(matchesJob(job('LIEPIN', { salaryMinK: 8, salaryMaxK: 12 }), filters)).toBe(false)
  expect(matchesJob(job('BOSS', { educationText: '大专' }), filters)).toBe(false)
  expect(matchesJob(job('BOSS', { salaryMinK: null, salaryMaxK: null, educationText: '大专' }), filters)).toBe(false)
  expect(matchesJob(job('BOSS', { salaryMinK: null, salaryMaxK: null }), filters)).toBe(true)
  expect(matchesJob(job('BOSS', { salaryMinK: null, salaryMaxK: null }), { ...filters, unknownSalary: false })).toBe(false)
  expect(matchesJob(job(), { ...filters, experience: '不限' })).toBe(false)
  expect(matchesJob(job('BOSS', { experienceText: null }), { ...filters, experience: '不限' })).toBe(true)
  expect(matchesJob(job('BOSS', { experienceText: null }), { ...filters, unknownExperience: false })).toBe(false)
  expect(matchesJob(job('BOSS', { educationText: null }), { ...filters, unknownEducation: false })).toBe(false)
})
it('要求选项保留原文和首次出现顺序，不把不限当作未知', () => {
  expect(originalOptions([job(), job('LIEPIN', { experienceText: ' 不限 ' }), job('ZHAOPIN', { experienceText: '3–10年' })], 'experienceText')).toEqual(['3–10年', '不限'])
})
it('城市不同即使区名相同也不误折叠，新增来源按站内顺序交替', () => {
  expect(groupJobs([job('BOSS'), job('LIEPIN', { city: '广州' })])).toHaveLength(2)
  const second = job('BOSS', { sourceId: 'second', key: 'BOSS:second' })
  expect(deduplicateJobs([job(), second, job('LIEPIN')]).map(item => item.key)).toEqual(['BOSS:same', 'LIEPIN:same', 'BOSS:second'])
})
it('同平台不同 ID 即使完整内容相同也保留独立组，每组至多一个同平台来源', () => {
  const groups = groupJobs([job(), job('BOSS', { key: 'BOSS:another', sourceId: 'another' }), job('LIEPIN')])
  expect(groups).toHaveLength(2)
  expect(groups.flatMap(group => group.sources.map(item => item.key)).sort()).toEqual(['BOSS:another', 'BOSS:same', 'LIEPIN:same'])
  for (const group of groups) expect(new Set(group.sources.map(item => item.source)).size).toBe(group.sources.length)
})
