import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory,
  type RouteRecordRaw,
} from 'vue-router'

import { session } from './auth/session'
import AdminLayout from './layouts/AdminLayout.vue'
import StudentLayout from './layouts/StudentLayout.vue'
import ChatView from './views/ChatView.vue'
import LoginView from './views/LoginView.vue'
import ProfileView from './views/ProfileView.vue'
import UgcView from './views/UgcView.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true },
  },
  {
    path: '/',
    component: StudentLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: { name: 'chat' } },
      { path: 'chat', name: 'chat', component: ChatView },
      { path: 'profile', name: 'profile', component: ProfileView },
      { path: 'ugc', name: 'ugc', component: UgcView },
    ],
  },
  {
    path: '/admin',
    name: 'admin',
    component: AdminLayout,
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'admin-overview',
        component: () => import('./views/admin/AdminOverviewView.vue'),
      },
      {
        path: 'sources',
        name: 'admin-sources',
        component: () => import('./views/admin/AdminSourcesView.vue'),
      },
      {
        path: 'imports',
        name: 'admin-imports',
        component: () => import('./views/admin/AdminImportsView.vue'),
      },
      {
        path: 'documents',
        name: 'admin-documents',
        component: () => import('./views/admin/AdminDocumentsView.vue'),
      },
      {
        path: 'moderation',
        name: 'admin-moderation',
        component: () => import('./views/admin/AdminModerationView.vue'),
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('./views/admin/AdminUsersView.vue'),
      },
      {
        path: 'database',
        name: 'admin-database',
        component: () => import('./views/admin/AdminDatabaseView.vue'),
      },
      {
        path: 'backups',
        name: 'admin-backups',
        component: () => import('./views/admin/AdminBackupsView.vue'),
      },
      {
        path: 'models',
        name: 'admin-models',
        component: () => import('./views/admin/AdminModelsView.vue'),
      },
      {
        path: 'audit',
        name: 'admin-audit',
        component: () => import('./views/admin/AdminAuditView.vue'),
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: { name: 'chat' },
  },
]

function roleHome(): { name: 'admin-overview' | 'chat' } {
  return session.user.value?.role === 'ADMIN'
    ? { name: 'admin-overview' }
    : { name: 'chat' }
}

export function createAppRouter(
  history: RouterHistory = createWebHistory(),
): Router {
  const router = createRouter({
    history,
    routes,
    scrollBehavior: () => ({ top: 0 }),
  })

  router.beforeEach(async (to) => {
    if (!session.ready.value) {
      try {
        await session.restore()
      } catch {
// 403 会保留已经建立的身份。冷启动时没有可信角色，因此由登录路由展示错误。
        if (!session.user.value && to.name !== 'login') {
          return {
            name: 'login',
            query: { redirect: to.fullPath },
          }
        }
      }
    }

    const user = session.user.value
    if (to.meta.public) {
      return user ? roleHome() : true
    }
    if (!user) {
      return {
        name: 'login',
        query: { redirect: to.fullPath },
      }
    }
    if (
      to.matched.some((record) => record.meta.requiresAdmin)
      && user.role !== 'ADMIN'
    ) {
      return { name: 'chat' }
    }
    return true
  })

  return router
}

export default createAppRouter()
