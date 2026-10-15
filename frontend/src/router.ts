import { createRouter, createWebHistory } from 'vue-router'
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./pages/Dashboard.vue'), meta: { title: '练习概览' } },
    { path: '/configure/:mode(quick|resume|project)', component: () => import('./pages/Configure.vue'), meta: { title: '准备面试' } },
    { path: '/materials', component: () => import('./pages/Materials.vue'), meta: { title: '个人资料库' } },
    { path: '/history', component: () => import('./pages/History.vue'), meta: { title: '面试历史' } },
    { path: '/sessions/:id', component: () => import('./pages/Session.vue'), meta: { title: '面试工作台' } },
    { path: '/reports/:id', component: () => import('./pages/Report.vue'), meta: { title: '面试复盘' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
