import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/home',
    children: [
      { path: 'home', name: 'home', component: () => import('../views/Home.vue'), meta: { title: '首页' } },
      // M01
      { path: 'datasource', name: 'datasource', component: () => import('../views/Datasource.vue'), meta: { title: '数据源接入' } },
      // M02
      { path: 'dataset', name: 'dataset', component: () => import('../views/Dataset.vue'), meta: { title: '数据集管理' } },
      // M03
      { path: 'designer', name: 'designer', component: () => import('../views/Designer.vue'), meta: { title: '报表设计器' } },
      { path: 'reports', name: 'reports', component: () => import('../views/Reports.vue'), meta: { title: '报表管理' } },
      // M04
      { path: 'params', name: 'params', component: () => import('../views/Params.vue'), meta: { title: '参数管理' } },
      // M05
      { path: 'charts', name: 'charts', component: () => import('../views/Charts.vue'), meta: { title: '可视化图表' } },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '仪表板大屏' } },
      // M06
      { path: 'form', name: 'form', component: () => import('../views/FormFill.vue'), meta: { title: '数据填报' } },
      // M07
      { path: 'analysis', name: 'analysis', component: () => import('../views/Analysis.vue'), meta: { title: '自助分析' } },
      // M08
      { path: 'metrics', name: 'metrics', component: () => import('../views/Metrics.vue'), meta: { title: '指标管理' } },
      // M09
      { path: 'admin/users', name: 'users', component: () => import('../views/Users.vue'), meta: { title: '用户与角色' } },
      { path: 'admin/directory', name: 'directory', component: () => import('../views/Directory.vue'), meta: { title: '目录与权限' } },
      { path: 'admin/schedule', name: 'schedule', component: () => import('../views/Schedule.vue'), meta: { title: '定时调度' } },
      { path: 'admin/logs', name: 'logs', component: () => import('../views/Logs.vue'), meta: { title: '操作日志' } },
      // M10
      { path: 'ops', name: 'ops', component: () => import('../views/Ops.vue'), meta: { title: '运维监控' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.public) return next()
  const token = localStorage.getItem('rbi_token')
  if (!token) return next('/login')
  next()
})

export default router
