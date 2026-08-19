<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api/http'
import {
  connect,
  isConnected,
  onStatusChange,
  sendSwap,
  subscribeErrors,
  subscribeRoom,
  subscribeSnapshot,
} from '@/api/ws'
import { useAuthStore } from '@/stores/auth'
import { BOARD_SIZE, ERROR_MESSAGE } from '@/utils/constants'
import type {
  CellState,
  ClearedData,
  ErrorData,
  GameEvent,
  GameOverData,
  MovedData,
  PlayerInfo,
  SnapshotData,
  Tile,
} from '@/types/game'
import GameBoard from '@/components/GameBoard.vue'
import PlayerPanel from '@/components/PlayerPanel.vue'
import CountdownBar from '@/components/CountdownBar.vue'
import ResultModal from '@/components/ResultModal.vue'

/**
 * 对局页:订阅房间事件流,棋盘/比分/倒计时全部以服务端事件为准。
 * moved/cleared/reshuffled 按顺序入队回放(交换 200ms → 每连锁步消除 250ms +
 * 下落 300ms),保证动画与棋盘状态一致。刷新或断线重连后,订阅自动恢复 +
 * 服务端重推快照,即完成恢复;对手离线由服务端立即结算,gameover 弹窗回大厅。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const roomId = String(route.params.roomId)

const snapshot = ref<SnapshotData | null>(null)
const tiles = ref<Tile[]>([])
const selected = ref<number[]>([])
const over = ref<GameOverData | null>(null)
const connected = ref(isConnected())

const unsubscribers: (() => void)[] = []
let snapshotTimer: ReturnType<typeof setTimeout> | null = null

// ---- 事件回放队列:动画按节奏依次播放,快照到达时整体重置 ----
const queue: (() => Promise<void>)[] = []
let pumping = false
let queueToken = 0
let tileKey = 0

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function enqueue(task: () => Promise<void>): void {
  queue.push(task)
  void pump()
}

async function pump(): Promise<void> {
  if (pumping) return
  pumping = true
  while (queue.length > 0) {
    const task = queue.shift()!
    await task()
  }
  pumping = false
}

const meId = computed(() => auth.user?.id ?? 0)
const players = computed(() => snapshot.value?.players ?? null)
const waiting = computed(() => snapshot.value?.status === 'waiting')
const playing = computed(() => snapshot.value?.status === 'playing')

const meInfo = computed<PlayerInfo | null>(() => {
  if (!players.value) return null
  return players.value.a.id === meId.value ? players.value.a : players.value.b
})

const oppInfo = computed<PlayerInfo | null>(() => {
  if (!players.value) return null
  return players.value.a.id === meId.value ? players.value.b : players.value.a
})

const myScore = computed(() => {
  if (!snapshot.value || !players.value) return 0
  return players.value.a.id === meId.value ? snapshot.value.scoreA : snapshot.value.scoreB
})

const oppScore = computed(() => {
  if (!snapshot.value || !players.value) return 0
  return players.value.a.id === meId.value ? snapshot.value.scoreB : snapshot.value.scoreA
})

/** 兜底:等不到快照时核对服务端对局状态(对局结束后的刷新 / 房间已结算) */
async function checkRoomAlive(): Promise<void> {
  if (snapshot.value || over.value) return
  try {
    const current = await api.currentGame()
    if (current.roomId) {
      if (current.roomId !== roomId) {
        router.replace({ name: 'game', params: { roomId: current.roomId } })
      }
    } else {
      ElMessage.info('对局已结束')
      router.replace({ name: 'lobby' })
    }
  } catch {
    /* 后端暂不可用(如服务重启中),重连成功后再补查 */
  }
}

onMounted(async () => {
  if (!isConnected()) connect()
  unsubscribers.push(
    onStatusChange((ok) => {
      connected.value = ok
      if (ok) {
        ElMessage.success('连接已恢复,对局继续')
        // 重连后 2 秒仍无快照 → 房间可能已不存在
        setTimeout(() => void checkRoomAlive(), 2000)
      }
    }),
  )
  unsubscribers.push(subscribeSnapshot(onSnapshot))
  unsubscribers.push(subscribeErrors(onError))
  unsubscribers.push(subscribeRoom(roomId, onRoomEvent))

  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      return
    }
  }
  // 首次进入的兜底:3 秒内没收到快照说明房间已不存在
  snapshotTimer = setTimeout(() => void checkRoomAlive(), 3000)
})

onUnmounted(() => {
  unsubscribers.forEach((u) => u())
  if (snapshotTimer) clearTimeout(snapshotTimer)
})

function onSnapshot(data: SnapshotData) {
  snapshot.value = data
  // 快照是权威整体状态:作废未播完的动画,直接重建棋盘
  queueToken += 1
  queue.length = 0
  tiles.value = tilesFromBoard(data.board)
}

function onError(data: ErrorData) {
  selected.value = []
  ElMessage.error(ERROR_MESSAGE[data.code] ?? data.message)
}

function onRoomEvent(event: GameEvent) {
  switch (event.type) {
    case 'moved': {
      const d = event.data as MovedData
      updateScores(d.scoreA, d.scoreB)
      const token = queueToken
      enqueue(async () => {
        if (token !== queueToken) return
        // 交换两格位置(key 不变 → FLIP 播放交换动画)
        const a = tiles.value.find((t) => t.id === d.from)
        const b = tiles.value.find((t) => t.id === d.to)
        if (a && b) {
          a.id = d.to
          b.id = d.from
          tiles.value = sortById([...tiles.value])
        }
        await sleep(200)
      })
      break
    }
    case 'cleared': {
      const d = event.data as ClearedData
      updateScores(d.scoreA, d.scoreB)
      const token = queueToken
      enqueue(async () => {
        if (token !== queueToken) return
        // 1. 消除动画:标记 dying
        const eliminated = new Set(d.cells)
        for (const t of tiles.value) {
          if (eliminated.has(t.id)) t.dying = true
        }
        await sleep(250)
        if (token !== queueToken) return
        // 2. 下落 + 补块:存活方块沿用 key(FLIP 下落),顶部补块换新 key(入场)
        tiles.value = rebuildBoard(d.board, eliminated)
        await sleep(300)
      })
      break
    }
    case 'reshuffled': {
      const d = event.data as { board: CellState[] }
      ElMessage.info('棋盘无路可走,已自动洗牌')
      const token = queueToken
      enqueue(async () => {
        if (token !== queueToken) return
        tiles.value = tilesFromBoard(d.board)
        selected.value = []
        await sleep(300)
      })
      break
    }
    case 'started': {
      const d = event.data as { startedAt: number; deadline: number }
      if (snapshot.value) {
        snapshot.value.status = 'playing'
        snapshot.value.startedAt = d.startedAt
        snapshot.value.deadline = d.deadline
      }
      break
    }
    case 'gameover':
      over.value = event.data as GameOverData
      break
  }
}

function updateScores(scoreA: number, scoreB: number) {
  if (snapshot.value) {
    snapshot.value.scoreA = scoreA
    snapshot.value.scoreB = scoreB
  }
}

// ---- 棋盘重建工具 ----

/** 快照/洗牌:整盘全新方块 */
function tilesFromBoard(board: CellState[]): Tile[] {
  return sortById(
    board.map((c) => ({ key: ++tileKey, id: c.id, emoji: c.emoji })),
  )
}

function sortById(list: Tile[]): Tile[] {
  return [...list].sort((a, b) => a.id - b.id)
}

/**
 * 按"消除 → 下落 → 补块"重建方块列表:
 * 每列自底向上收集存活方块(保留 key,下落为 FLIP 动画),顶部不足补新方块。
 */
function rebuildBoard(board: CellState[], eliminated: Set<number>): Tile[] {
  const byId = new Map(tiles.value.map((t) => [t.id, t]))
  const next: Tile[] = []
  for (let c = 0; c < BOARD_SIZE; c++) {
    const kept: Tile[] = []
    for (let r = BOARD_SIZE - 1; r >= 0; r--) {
      const id = r * BOARD_SIZE + c
      const tile = byId.get(id)
      if (tile && !eliminated.has(id)) kept.push(tile)
    }
    const column = board
      .filter((cell) => cell.id % BOARD_SIZE === c)
      .sort((x, y) => y.id - x.id) // 自底向上
    let ki = 0
    for (const cell of column) {
      if (ki < kept.length) {
        const tile = kept[ki++]
        tile.id = cell.id
        tile.emoji = cell.emoji
        next.push(tile)
      } else {
        next.push({ key: ++tileKey, id: cell.id, emoji: cell.emoji })
      }
    }
  }
  return sortById(next)
}

// ---- 交互 ----

function isAdjacent(a: number, b: number): boolean {
  return (
    Math.abs(Math.floor(a / BOARD_SIZE) - Math.floor(b / BOARD_SIZE)) +
      Math.abs((a % BOARD_SIZE) - (b % BOARD_SIZE)) ===
    1
  )
}

/** 点选(原地按下松开):两个相邻格交换,不相邻则改为选中新格 */
function onCellClick(id: number) {
  if (!meId.value || waiting.value || over.value) return
  if (selected.value.includes(id)) {
    selected.value = []
    return
  }
  if (selected.value.length === 1) {
    const a = selected.value[0]
    if (a !== id && isAdjacent(a, id)) {
      selected.value = []
      sendSwap(a, id)
    } else if (a !== id) {
      ElMessage.warning(ERROR_MESSAGE[42200])
      selected.value = [id]
    }
    return
  }
  selected.value = [id]
}

/** 拖动交换:合法性由服务端最终判定,非法会经 /user/queue/errors 提示 */
function onSwap(from: number, to: number) {
  if (!meId.value || waiting.value || over.value) return
  selected.value = []
  sendSwap(from, to)
}

function backToLobby() {
  router.replace({ name: 'lobby' })
}
</script>

<template>
  <div class="game-page">
    <header class="topbar">
      <div class="brand">🍎 Link-Duel</div>
      <div class="room">房间 {{ roomId }}</div>
      <el-tag :type="connected ? 'success' : 'danger'" size="small">
        {{ connected ? '已连接' : '断线重连中…' }}
      </el-tag>
    </header>

    <div v-if="!connected" class="banner reconnect">
      网络连接已断开,正在自动重连…对局状态保存在服务器,恢复后自动继续
    </div>

    <main v-if="snapshot && meInfo && oppInfo" class="arena">
      <PlayerPanel :player="oppInfo" :score="oppScore" :me="false" />
      <CountdownBar
        v-if="playing"
        :deadline="snapshot.deadline"
        :total-ms="snapshot.deadline - snapshot.startedAt"
      />
      <div class="board-wrap">
        <GameBoard
          :tiles="tiles"
          :selected="selected"
          @cell-click="onCellClick"
          @swap="onSwap"
        />
        <div v-if="waiting" class="waiting-mask">
          <p>⏳ 等待对手进入…</p>
        </div>
      </div>
      <PlayerPanel :player="meInfo" :score="myScore" :me="true" />
    </main>

    <div v-else class="loading">对局载入中…</div>

    <ResultModal
      v-if="over && players"
      :over="over"
      :me-id="meId"
      :players="players"
      @back="backToLobby"
    />
  </div>
</template>

<style scoped>
.game-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.brand {
  font-size: 20px;
  font-weight: 700;
  margin-right: auto;
}

.room {
  color: #909399;
  font-size: 13px;
}

.banner {
  text-align: center;
  padding: 8px;
  font-size: 13px;
}

.banner.reconnect {
  background: #fef0f0;
  color: #f56c6c;
}

.arena {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 16px;
}

.board-wrap {
  position: relative;
}

.waiting-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.65);
  border-radius: 12px;
  color: #fff;
  font-size: 18px;
}

.loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}
</style>
