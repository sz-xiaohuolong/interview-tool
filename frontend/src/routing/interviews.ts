import type { Router } from 'vue-router'

export function registerInterviewRoutes(router: Router) {
  const remove = [
    router.addRoute({ path: '/configure/quick', component: () => import('../pages/QuickPracticeConfigure.vue'), meta: { title: '快速练习' } }),
    router.addRoute({ path: '/interviews', alias: '/interview-v3', component: () => import('../pages/InterviewConfigure.vue'), meta: { title: '综合面试' } }),
    router.addRoute({ path: '/interviews/:id', alias: '/interview-v3/:id', component: () => import('../pages/InterviewSession.vue'), meta: { title: '综合面试工作台' } }),
    router.addRoute({ path: '/history/interviews/:id/replay', alias: '/history/v3/:id/replay', component: () => import('../pages/Replay.vue'), props: { source: 'interview' }, meta: { title: '对话回放' } }),
  ]
  return () => remove.forEach(dispose => dispose())
}
