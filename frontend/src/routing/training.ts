import type { Router } from 'vue-router'

export function registerTrainingRoutes(router: Router) {
  const remove = [
    ...['/jd-diagnostics', '/jd-diagnostics/:id', '/sprints/:id'].map(path => router.addRoute({ path, component: () => import('../pages/training/JdDiagnostics.vue'), meta: { title: '岗位诊断' } })),
    ...['/stories', '/stories/:id'].map(path => router.addRoute({ path, component: () => import('../pages/training/Stories.vue'), meta: { title: '项目经历' } })),
    ...['/design-practice', '/design-practice/:id'].map(path => router.addRoute({ path, component: () => import('../pages/training/DesignPractice.vue'), meta: { title: '系统设计' } })),
    ...['/review', '/review/:id', '/practice', '/practice/:id'].map(path => router.addRoute({ path, component: () => import('../pages/training/TrainingWorkbench.vue'), meta: { title: '错题重练', trainingWorkspace: true } })),
    router.addRoute({ path: '/skills', component: () => import('../pages/training/Skills.vue'), meta: { title: '能力看板' } }),
  ]
  return () => remove.forEach(fn => fn())
}
