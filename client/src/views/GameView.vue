<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api/http'
import {
  connect,
  isConnected,
  onStatusChange,
  sendMove,
  subscribeErrors,
  subscribeRoom,
  subscribeSnapshot,
} from '@/api/ws'
import { useAuthStore } from '@/stores/auth'
import { findPath } from '@/utils/pathValidator'
import { BOARD_SIZE, ERROR_MESSAGE } from '@/utils/constants'
import type {
  CellState,
  EliminatedData,
  ErrorData,
  GameEvent,
  GameOverData,
  PlayerInfo,
  SnapshotData,
} from '@/types/game'
import GameBoard from '@/components/GameBoard.vue'
import PlayerPanel from '@/components/PlayerPanel.vue'
import CountdownBar from '@/components/CountdownBar.vue'
import ResultModal from '@/components/ResultModal.vue'

/**
 * 对局页:订阅房间事件流,棋盘/比分/倒计时全部以服务端事件为准。
 * 刷新或断线重连后,订阅自动恢复 + 服务端重推快照,即完成恢复。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const roomId = String(route.params.roomId)

const snapshot = ref<SnapshotData | null>(null)
const board = ref<CellState[]>([])
const selected = ref<number[]>([])
const elimination = ref<{ path: number[]; emoji: string } | null>(null)
const over = ref<GameOverData | null>(null)
const connected = ref(isConnected())
const opponentOffline = ref(false)

const unsubscribers: (() => void)[] = []
let snapshotTimer: ReturnType<typeof setTimeout> | null = null
let elimToken = 0

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

onMounted(async () => {
  if (!isConnected()) connect()
  unsubscribers.push(
    onStatusChange((ok) => {
      connected.value = ok
      if (ok) ElMessage.success('连接已恢复,对局继续')
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
  // 兜底:3 秒内没收到快照说明房间已不存在(对局结束后的刷新等)
  snapshotTimer = setTimeout(async () => {
    if (!snapshot.value && !over.value) {
      try {
        const current = await api.currentGame()
        if (!current.roomId) {
          ElMessage.info('对局已结束')
          router.replace({ name: 'lobby' })
        }
      } catch {
        /* 忽略 */
      }
    }
  }, 3000)
})

onUnmounted(() => {
  unsubscribers.forEach((u) => u())
  if (snapshotTimer) clearTimeout(snapshotTimer)
})

function onSnapshot(data: SnapshotData) {
  snapshot.value = data
  board.value = data.board.map((c) => ({ ...c }))
  opponentOffline.value = !oppInfo.value?.online
}

function onError(data: ErrorData) {
  selected.value = []
  ElMessage.error(ERROR_MESSAGE[data.code] ?? data.message)
}

function onRoomEvent(event: GameEvent) {
  switch (event.type) {
    case 'eliminated': {
      const d = event.data as EliminatedData
      if (snapshot.value) {
        snapshot.value.scoreA = d.scoreA
        snapshot.value.scoreB = d.scoreB
      }
      const token = ++elimToken
      elimination.value = { path: d.path, emoji: d.emoji }
      setTimeout(() => {
        // 棋盘状态变更始终生效;动画清除仅在"仍是最新动画"时执行
        const a = board.value[d.cellA]
        const b = board.value[d.cellB]
        if (a) a.eliminated = true
        if (b) b.eliminated = true
        if (elimToken === token) elimination.value = null
      }, 650)
      break
    }
    case 'reshuffled': {
      const d = event.data as { board: CellState[] }
      board.value = d.board.map((c) => ({ ...c }))
      selected.value = []
      ElMessage.info('棋盘无路可走,已自动洗牌')
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
    case 'player-online': {
      const d = event.data as { userId: number }
      if (d.userId === meId.value) break
      opponentOffline.value = false
      ElMessage.info('对手已重新连接')
      break
    }
    case 'player-offline': {
      const d = event.data as { userId: number }
      if (d.userId === meId.value) break
      opponentOffline.value = true
      ElMessage.warning('对手已离线(90 秒宽限期,期间可重连)')
      break
    }
    case 'gameover':
      over.value = event.data as GameOverData
      break
  }
}

function onCellClick(id: number) {
  if (!meId.value || waiting.value || over.value) return
  const cell = board.value[id]
  if (!cell || cell.eliminated) return
  if (selected.value.includes(id)) {
    selected.value = selected.value.filter((s) => s !== id)
    return
  }
  if (selected.value.length === 1) {
    const a = selected.value[0]
    if (findPath(board.value, BOARD_SIZE, a, id)) {
      selected.value = []
      sendMove(a, id)
    } else {
      ElMessage.warning(ERROR_MESSAGE[42200])
      selected.value = [id]
    }
    return
  }
  selected.value = [id]
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
    <div v-if="connected && opponentOffline" class="banner offline">
      对手已离线(90 秒宽限期,超时将判你获胜)
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
          :board="board"
          :selected="selected"
          :elimination="elimination"
          @cell-click="onCellClick"
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

.banner.offline {
  background: #fdf6ec;
  color: #e6a23c;
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
