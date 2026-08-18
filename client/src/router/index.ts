import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/api/http'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', name: 'lobby', component: () => import('@/views/LobbyView.vue') },
    { path: '/game/:roomId', name: 'game', component: () => import('@/views/GameView.vue') },
  ],
})

router.beforeEach((to) => {
  const loggedIn = Boolean(getToken())
  if (!loggedIn && to.name !== 'login') {
    return { name: 'login' }
  }
  if (loggedIn && to.name === 'login') {
    return { name: 'lobby' }
  }
})

export default router
