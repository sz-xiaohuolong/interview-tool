export type PrimarySection = 'overview' | 'interview' | 'materials' | 'history' | 'training'
export interface LocationStep { title: string; to?: string }

export const trainingNavigation = [
  { to: '/review', title: '错题复习', detailTitle: '复习详情' },
  { to: '/skills', title: '能力看板', detailTitle: '能力详情' },
  { to: '/practice', title: '原题重练', detailTitle: '作答详情' },
  { to: '/jd-diagnostics', title: '岗位诊断', detailTitle: '诊断详情' },
  { to: '/stories', title: '项目经历', detailTitle: '经历详情' },
  { to: '/design-practice', title: '系统设计', detailTitle: '训练详情' },
]

function belongsTo(path: string, root: string) { return path === root || path.startsWith(`${root}/`) }

export function navigationFor(path: string, pageTitle = '') {
  path = path.toLowerCase().replace(/\/+$/, '') || '/'
  const training = trainingNavigation.find(item => belongsTo(path, item.to)
    || (item.to === '/jd-diagnostics' && belongsTo(path, '/sprints')))
  if (training) {
    const detail = path !== training.to
    const breadcrumbs: LocationStep[] = [{ title: '专项训练', to: '/review' },
      { title: training.title, ...(detail ? { to: training.to } : {}) }]
    if (detail) breadcrumbs.push({ title: belongsTo(path, '/sprints') ? '训练清单' : training.detailTitle })
    return { primary: 'training' as PrimarySection, training, breadcrumbs }
  }
  let primary: PrimarySection = 'overview'
  let breadcrumbs: LocationStep[] = [{ title: pageTitle || '练习概览' }]
  if (belongsTo(path, '/configure')) {
    primary = 'interview'
    breadcrumbs = [{ title: '开始面试', to: '/configure/quick' }, { title: pageTitle || '准备面试' }]
  } else if (belongsTo(path, '/sessions')) {
    primary = 'interview'
    breadcrumbs = [{ title: '开始面试', to: '/configure/quick' }, { title: pageTitle || '面试工作台' }]
  } else if (belongsTo(path, '/reports')) {
    primary = 'history'
    breadcrumbs = [{ title: '面试历史', to: '/history' }, { title: pageTitle || '面试复盘' }]
  } else if (belongsTo(path, '/history')) {
    primary = 'history'
    breadcrumbs = [{ title: '面试历史' }]
  } else if (belongsTo(path, '/materials')) {
    primary = 'materials'
    breadcrumbs = [{ title: '个人资料库' }]
  }
  return { primary, training: undefined, breadcrumbs }
}
