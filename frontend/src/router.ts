import { createRouter, createWebHistory } from 'vue-router'
import { registerTrainingRoutes } from './routing/training'
import { registerInterviewRoutes } from './routing/interviews'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./pages/home/Dashboard.vue'), meta: { title: '练习概览' } },
    { path: '/configure/:mode(quick|resume|project)', component: () => import('./pages/interview/FocusedInterviewConfigure.vue'), meta: { title: '准备面试' } },
    { path: '/materials', component: () => import('./pages/material/Materials.vue'), meta: { title: '个人资料库' } },
    { path: '/job-applications', component: () => import('./pages/jobs/JobApplications.vue'), meta: { title: '求职管理' } },
    { path: '/history', component: () => import('./pages/history/History.vue'), meta: { title: '面试历史' } },
    { path: '/history/:id/replay', component: () => import('./pages/history/Replay.vue'), meta: { title: '对话回放' } },
    { path: '/sessions/:id', component: () => import('./pages/interview/FocusedInterviewSession.vue'), meta: { title: '面试工作台' } },
    { path: '/reports/:id', component: () => import('./pages/interview/FocusedInterviewReport.vue'), meta: { title: '面试复盘' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: (to, _from, savedPosition) => {
    if (to.meta.trainingWorkspace) {
      if (savedPosition) return savedPosition
      if (!to.params.id && !to.query.id) {
        try { return { top: JSON.parse(sessionStorage.getItem('training:workbench:view') || 'null')?.pageScroll || 0 } } catch { /* 无缓存时回到页首。 */ }
      }
    }
    return { top: 0 }
  },
})

registerInterviewRoutes(router)
registerTrainingRoutes(router)
export default router
