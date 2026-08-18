import { Client, type IMessage } from '@stomp/stompjs'
import type {
  ErrorData,
  GameEvent,
  MatchFoundData,
  SnapshotData,
} from '@/types/game'
import { getToken } from './http'

/**
 * STOMP 连接单例:
 * <ul>
 *   <li>断线自定义退避重连(1s/2s/4s/8s 封顶),不用 stompjs 内置重连,
 *       以便 UI 展示"断线 → 重连 → 恢复"过程;</li>
 *   <li>所有订阅登记在册,重连成功后自动恢复订阅(对局快照即恢复);</li>
 *   <li>心跳 5s 一次,服务端回 serverNow,据此计算时钟偏移校准倒计时。</li>
 * </ul>
 */

const BACKOFF_MS = [1000, 2000, 4000, 8000]
const HEARTBEAT_INTERVAL = 5000

interface SubscriptionSpec {
  destination: string
  handler: (event: GameEvent) => void
}

let client: Client | null = null
let attempt = 0
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let statusCallback: ((connected: boolean) => void) | null = null
/** 客户端时钟偏移:serverNow - Date.now() */
let serverOffset = 0
const subscriptions: SubscriptionSpec[] = []

function wsUrl(): string {
  // 开发环境由 Vite 代理 /ws → 后端 8080;生产同源部署
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${window.location.host}/ws`
}

/** 校准后的服务器当前时间(倒计时以它为准,不受本机时钟影响) */
export function serverNow(): number {
  return Date.now() + serverOffset
}

export function isConnected(): boolean {
  return client?.connected ?? false
}

/** 监听连接状态变化(返回取消函数) */
export function onStatusChange(cb: (connected: boolean) => void): () => void {
  statusCallback = cb
  return () => {
    if (statusCallback === cb) statusCallback = null
  }
}

export function connect(): void {
  const token = getToken()
  if (!token) return
  disconnect()

  const newClient = new Client({
    brokerURL: wsUrl(),
    connectHeaders: { Authorization: `Bearer ${token}` },
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    reconnectDelay: 0, // 自己控制退避
  })

  newClient.onConnect = () => {
    attempt = 0
    // 重连成功:恢复全部订阅(快照会自动重新推送)
    subscriptions.forEach(doSubscribe)
    startHeartbeat()
    statusCallback?.(true)
  }
  newClient.onDisconnect = () => {
    stopHeartbeat()
    statusCallback?.(false)
    // 只有"当前连接"断开才调度重连(手动 disconnect/新连接替换时不再重连)
    if (client === newClient) scheduleReconnect()
  }
  newClient.onWebSocketError = () => {
    /* 统一由 onDisconnect 处理 */
  }
  newClient.onStompError = () => {
    /* 协议错误(如订阅已结束对局)不致命,等待后续恢复流程 */
  }
  client = newClient
  client.activate()
}

/** 手动断开(退出登录):不再自动重连 */
export function disconnect(): void {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  stopHeartbeat()
  const old = client
  client = null
  if (old) {
    void old.deactivate()
  }
}

function scheduleReconnect(): void {
  if (reconnectTimer) return
  const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]
  attempt += 1
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, delay)
}

function startHeartbeat(): void {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    try {
      client?.publish({ destination: '/app/heartbeat', body: '{}' })
    } catch {
      /* 连接已断开时忽略 */
    }
  }, HEARTBEAT_INTERVAL)
}

function stopHeartbeat(): void {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function doSubscribe(spec: SubscriptionSpec): void {
  if (!client?.connected) return
  client.subscribe(spec.destination, (msg: IMessage) => {
    const event = JSON.parse(msg.body) as GameEvent
    // 每个事件都带服务端时间,持续校准时钟偏移(倒计时不受本机时钟影响)
    if (typeof event.serverNow === 'number') {
      serverOffset = event.serverNow - Date.now()
    }
    spec.handler(event)
  })
}

/** 登记订阅:立即订阅(若已连接)并记入清单,重连后自动恢复 */
function register(destination: string, handler: (event: GameEvent) => void): () => void {
  const spec: SubscriptionSpec = { destination, handler }
  subscriptions.push(spec)
  doSubscribe(spec)
  return () => {
    const i = subscriptions.indexOf(spec)
    if (i >= 0) subscriptions.splice(i, 1)
  }
}

// ---- 面向业务的下游接口 ----

/** 匹配成功通知(/user/queue/match) */
export function subscribeMatch(handler: (data: MatchFoundData) => void): () => void {
  return register('/user/queue/match', (e) => {
    if (e.type === 'match-found') handler(e.data as MatchFoundData)
  })
}

/** 对局错误通知(/user/queue/errors,如非法消除) */
export function subscribeErrors(handler: (data: ErrorData) => void): () => void {
  return register('/user/queue/errors', (e) => {
    if (e.type === 'error') handler(e.data as ErrorData)
  })
}

/** 全量快照(/user/queue/snapshot,订阅房间后服务端推送) */
export function subscribeSnapshot(handler: (data: SnapshotData) => void): () => void {
  return register('/user/queue/snapshot', (e) => {
    if (e.type === 'snapshot') handler(e.data as SnapshotData)
  })
}

/** 房间实时事件(/topic/game/{roomId}) */
export function subscribeRoom(roomId: string, handler: (event: GameEvent) => void): () => void {
  return register(`/topic/game/${roomId}`, handler)
}

/** 发送消除请求(roomId 由服务端从 user:game 推导,不可伪造) */
export function sendMove(cellA: number, cellB: number): void {
  client?.publish({
    destination: '/app/game/move',
    body: JSON.stringify({ cellA, cellB }),
  })
}
