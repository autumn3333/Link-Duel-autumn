// 与后端 dto 包一一对应的类型(所有时间为 epoch 毫秒)

export interface UserInfo {
  id: number
  email: string
  nickname: string
  points: number
}

/** 服务端棋盘格子:三消玩法棋盘永远全满,只有位置与图案 */
export interface CellState {
  id: number
  emoji: string
}

/**
 * 客户端棋盘方块:key 为稳定身份(跨棋盘更新保留),驱动 TransitionGroup
 * FLIP 动画——交换/下落时同一个 key 换位置即产生移动动画,补块才换新 key。
 */
export interface Tile {
  key: number
  id: number
  emoji: string
  /** 本连锁步被消除,播放消失动画后移除 */
  dying?: boolean
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
  players: { a: PlayerInfo; b: PlayerInfo }
}

/** moved 事件:交换动画 + 交换后的比分 */
export interface MovedData {
  byUserId: number
  from: number
  to: number
  scoreA: number
  scoreB: number
}

/** cleared 事件:一个连锁步的消除格子 + 该步下落补块后的完整棋盘 */
export interface ClearedData {
  byUserId: number
  cells: number[]
  board: CellState[]
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
