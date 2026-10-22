import { expect, it } from 'vitest'
import { localDateTime } from './job-applications'

it('本地日期时间在四位年份范围内保持可被表单重新编辑的格式', () => {
  expect(localDateTime(null)).toBe('')
  expect(localDateTime('0001-01-01T12:30:00Z')).toMatch(/^0001-01-01T\d{2}:\d{2}:\d{2}$/)
})
