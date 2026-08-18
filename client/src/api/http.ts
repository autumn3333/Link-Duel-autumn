import type { JoinResult, LeaderboardItem, UserInfo } from '@/types/game'

/** 统一 HTTP 封装:自动带 JWT、解包 Result{code,msg,data}、401 跳登录 */

interface Result<T> {
  code: number
  msg: string
  data: T
}

const TOKEN_KEY = 'linkduel_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const resp = await fetch(path, { ...options, headers })
  const body = (await resp.json()) as Result<T>
  if (body.code !== 0) {
    if (body.code === 40100) {
      clearToken()
      window.location.href = '/login'
    }
    throw new ApiError(body.code, body.msg)
  }
  return body.data
}

export const api = {
  login: (email: string, password: string) =>
    request<{ token: string; user: UserInfo }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  me: () => request<UserInfo>('/api/users/me'),
  joinMatch: () => request<JoinResult>('/api/match/join', { method: 'POST' }),
  cancelMatch: () => request<null>('/api/match/cancel', { method: 'POST' }),
  currentGame: () => request<{ roomId: string | null }>('/api/game/current'),
  leaderboard: (limit = 10) =>
    request<LeaderboardItem[]>(`/api/leaderboard?limit=${limit}`),
}
