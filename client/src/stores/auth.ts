import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, clearToken, getToken, setToken } from '@/api/http'
import { connect, disconnect } from '@/api/ws'
import type { UserInfo } from '@/types/game'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<UserInfo | null>(null)

  async function login(email: string, password: string) {
    const data = await api.login(email, password)
    setToken(data.token)
    token.value = data.token
    user.value = data.user
    // 登录即建立 STOMP 连接(匹配通知、对局事件都走它)
    connect()
  }

  function logout() {
    disconnect()
    clearToken()
    token.value = null
    user.value = null
  }

  /** 页面刷新后恢复用户信息(排行榜 isMe 高亮等依赖) */
  async function loadMe() {
    user.value = await api.me()
  }

  return { token, user, login, logout, loadMe }
})
