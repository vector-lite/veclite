import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/stores'
  },
  {
    path: '/stores',
    name: 'stores',
    component: () => import('../views/StoresView.vue'),
    meta: { title: '向量库管理 (Stores)' }
  },
  {
    path: '/documents',
    name: 'documents',
    component: () => import('../views/DocumentsView.vue'),
    meta: { title: '向量数据管理 (Data CRUD)' }
  },
  {
    path: '/search-debug',
    name: 'search-debug',
    component: () => import('../views/SearchDebugView.vue'),
    meta: { title: '向量查询 Debug 可视化' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/SettingsView.vue'),
    meta: { title: '系统设置 & API' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, _, next) => {
  if (to.meta.title) {
    document.title = `${to.meta.title} - VecLite View`
  }
  next()
})

export default router
