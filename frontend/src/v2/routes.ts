import type { Router } from 'vue-router'

export function registerV2(router: Router) {
  const remove = [
    ...['/jd-diagnostics', '/jd-diagnostics/:id', '/sprints/:id'].map(path => router.addRoute({ path, component: () => import('./JdPage.vue'), meta: { title: '岗位差距与短清单' } })),
    ...['/stories', '/stories/:id'].map(path => router.addRoute({ path, component: () => import('./StoryPage.vue'), meta: { title: '自我介绍与 STAR 故事' } })),
    ...['/design-practice', '/design-practice/:id'].map(path => router.addRoute({ path, component: () => import('./DesignPage.vue'), meta: { title: '分阶段设计训练' } })),
    router.addRoute({ path: '/practice', component: () => import('./PracticePage.vue'), meta: { title: '同题二答' } }),
    router.addRoute({ path: '/practice/:id', component: () => import('./PracticePage.vue'), meta: { title: '独立作答与对照' } }),
    router.addRoute({ path: '/review', component: () => import('./ReviewPage.vue'), meta: { title: '复习队列' } }),
    router.addRoute({ path: '/review/:id', component: () => import('./ReviewPage.vue'), meta: { title: '复习详情' } }),
    router.addRoute({ path: '/skills', component: () => import('./SkillsPage.vue'), meta: { title: '能力证据与自评' } }),
  ]
  return () => remove.forEach(fn => fn())
}
