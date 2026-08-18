// 与后端 dto 包一一对应的类型(所有时间为 epoch 毫秒)

export interface UserInfo {
  id: number
  email: string
  nickname: string
  points: number
}

export interface CellState {
  id: number
  emoji: string
  eliminated: boolean
}

export interface PlayerInfo {
  id: number
  nickname: string
  online: boolean
}

export interface SnapshotData {
  roomId: string
  status: 'waiting' | 'playing' | 'settled'
  board: CellState[]
  scoreA: number
  scoreB: number
  startedAt: number
  deadline: number
  reshuffleUsed: boolean
  players: { a: PlayerInfo; b: PlayerInfo }
}

export interface EliminatedData {
  byUserId: number
  cellA: number
  cellB: number
  emoji: string
  path: number[]
  scoreA: number
  scoreB: number
}

export interface GameOverData {
  winnerId: number | null
  scoreA: number
  scoreB: number
  reason: string
  status: 'finished' | 'forfeit' | 'cancelled'
  deltaA: number | null
  deltaB: number | null
}

export interface MatchFoundData {
  roomId: string
  opponent: { id: number; nickname: string }
}

export interface ErrorData {
  code: number
  message: string
}

/** STOMP 事件信封:{type, serverNow, data} */
export interface GameEvent<T = unknown> {
  type: string
  serverNow: number
  data: T
}

export interface JoinResult {
  status: 'queued' | 'matched'
  roomId: string | null
  opponent: UserInfo | null
}

export interface LeaderboardItem {
  rank: number
  userId: number
  nickname: string
  points: number
  isMe: boolean
}
