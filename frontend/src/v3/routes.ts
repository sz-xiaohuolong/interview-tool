import type { Router } from 'vue-router'

export function registerV3(router: Router) {
  const remove = [
    router.addRoute({ path: '/interview-v3', component: () => import('./Configure.vue'), meta: { title: '综合面试' } }),
    router.addRoute({ path: '/interview-v3/:id', component: () => import('./Session.vue'), meta: { title: '综合面试工作台' } }),
    router.beforeEach(to => {
      if (to.path === '/configure/quick' && !to.query.trainingLinkId) return { path: '/interview-v3', query: to.query, hash: to.hash, replace: true }
    }),
  ]
  return () => remove.forEach(dispose => dispose())
}
