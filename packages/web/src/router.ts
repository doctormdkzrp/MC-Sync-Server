import { createRouter, createWebHistory } from 'vue-router'
import { api, setUnauthorizedHandler } from './api'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('./views/Panel.vue'),
      children: [
        { path: '', redirect: { name: 'mods' } },
        { path: 'mods', name: 'mods', component: () => import('./views/Mods.vue') },
        { path: 'config', name: 'config', component: () => import('./views/Config.vue') },
        { path: 'other', name: 'other', component: () => import('./views/Other.vue') },
        { path: 'recycle', name: 'recycle', component: () => import('./views/Recycle.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

let authed: boolean | null = null

export function markAuthed(v: boolean): void {
  authed = v
}

setUnauthorizedHandler(() => {
  authed = false
  const cur = router.currentRoute.value
  if (cur.name !== 'login') {
    void router.replace({ name: 'login', query: cur.fullPath !== '/' ? { redirect: cur.fullPath } : {} })
  }
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (authed === null) {
    try {
      const s = await api.session()
      authed = !!s.authed
    } catch {
      authed = false
    }
  }
  if (!authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  return true
})

export default router
