import type { InjectionKey, Ref } from 'vue'
import type { InterviewSessionType } from './models/interview'

export interface InterviewNavigationContext { id: string; sessionType: InterviewSessionType }
export const interviewNavigationKey: InjectionKey<Ref<InterviewNavigationContext | null>> = Symbol('当前面试导航')

export type PrimarySection = 'overview' | 'quick' | 'interview' | 'materials' | 'history' | 'training' | 'jobs' | 'job-search'
export interface LocationStep { title: string; to?: string }

export const trainingNavigation = [
  { to: '/review', title: '错题重练', detailTitle: '练习详情' },
  { to: '/stories', title: '项目经历', detailTitle: '经历详情' },
]
// 历史页面继续可读，但不再占用日常专项入口。
const legacyTraining = [
  { to: '/skills', title: '能力看板', detailTitle: '能力详情' },
  { to: '/jd-diagnostics', title: '岗位诊断', detailTitle: '诊断详情' },
  { to: '/design-practice', title: '系统设计', detailTitle: '训练详情' },
]

function belongsTo(path: string, root: string) { return path === root || path.startsWith(`${root}/`) }

export function navigationFor(path: string, pageTitle = '', sessionType?: InterviewSessionType) {
  path = path.toLowerCase().replace(/\/+$/, '') || '/'
  // 兼容旧书签，导航归属仍使用当前业务路径。
  path = path.replace(/^\/interview-v3(?=\/|$)/, '/interviews')
  const training = [...trainingNavigation, ...legacyTraining].find(item => belongsTo(path, item.to)
    || (item.to === '/review' && belongsTo(path, '/practice'))
    || (item.to === '/jd-diagnostics' && belongsTo(path, '/sprints')))
  if (training) {
    const detail = path !== training.to && path !== '/practice'
    const breadcrumbs: LocationStep[] = [{ title: '专项训练', to: '/review' },
      { title: training.title, ...(detail ? { to: training.to } : {}) }]
    if (detail) breadcrumbs.push({ title: belongsTo(path, '/sprints') ? '训练清单' : training.detailTitle })
    return { primary: 'training' as PrimarySection, training, breadcrumbs }
  }
  let primary: PrimarySection = 'overview'
  let breadcrumbs: LocationStep[] = [{ title: pageTitle || '练习概览' }]
  if (path === '/configure/quick' || (belongsTo(path, '/interviews') && sessionType === 'QUICK')) {
    primary = 'quick'
    breadcrumbs = path === '/configure/quick' ? [{ title: '快速练习' }] : [{ title: '快速练习', to: '/configure/quick' }, { title: '练习工作台' }]
  } else if (belongsTo(path, '/configure') || belongsTo(path, '/interviews')) {
    primary = 'interview'
    breadcrumbs = [{ title: '综合面试', to: '/interviews' }, { title: pageTitle || '准备面试' }]
  } else if (belongsTo(path, '/sessions')) {
    primary = 'interview'
    breadcrumbs = [{ title: '综合面试', to: '/interviews' }, { title: pageTitle || '面试工作台' }]
  } else if (belongsTo(path, '/reports')) {
    primary = 'history'
    breadcrumbs = [{ title: '面试历史', to: '/history' }, { title: pageTitle || '面试复盘' }]
  } else if (belongsTo(path, '/history')) {
    primary = 'history'
    breadcrumbs = [{ title: '面试历史' }]
  } else if (belongsTo(path, '/job-search')) {
    primary = 'job-search'
    breadcrumbs = [{ title: '岗位搜索' }]
  } else if (belongsTo(path, '/job-applications')) {
    primary = 'jobs'
    breadcrumbs = [{ title: '求职管理' }]
  } else if (belongsTo(path, '/materials')) {
    primary = 'materials'
    breadcrumbs = [{ title: '个人资料库' }]
  }
  return { primary, training: undefined, breadcrumbs }
}
