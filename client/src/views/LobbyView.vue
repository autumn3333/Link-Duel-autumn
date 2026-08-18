<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '@/api/http'
import { onStatusChange, subscribeMatch } from '@/api/ws'
import { useAuthStore } from '@/stores/auth'
import type { MatchFoundData } from '@/types/game'
import LeaderboardPanel from '@/components/LeaderboardPanel.vue'

const auth = useAuthStore()
const router = useRouter()

const matching = ref(false)
const joining = ref(false)
const connected = ref(false)
let unsubscribeMatch: (() => void) | null = null
let unsubscribeStatus: (() => void) | null = null

function enterGame(roomId: string) {
  matching.value = false
  router.push({ name: 'game', params: { roomId } })
}

function onMatchFound(data: MatchFoundData) {
  enterGame(data.roomId)
}

onMounted(async () => {
  unsubscribeMatch = subscribeMatch(onMatchFound)
  unsubscribeStatus = onStatusChange(async (ok) => {
    connected.value = ok
    // 重连成功且还在排队:补查一次对局状态(防止错过匹配通知)
    if (ok && matching.value) {
      try {
        const current = await api.currentGame()
        if (current.roomId) enterGame(current.roomId)
      } catch {
        /* 忽略,下次事件再补 */
      }
    }
  })
  try {
    await auth.loadMe()
    const current = await api.currentGame()
    if (current.roomId) {
      ElMessage.info('检测到未结束的对局,正在恢复…')
      enterGame(current.roomId)
    }
  } catch {
    /* 401 已由 http 层跳转登录 */
  }
})

onUnmounted(() => {
  unsubscribeMatch?.()
  unsubscribeStatus?.()
})

async function joinMatch() {
  joining.value = true
  try {
    const result = await api.joinMatch()
    if (result.status === 'matched' && result.roomId) {
      enterGame(result.roomId)
    } else {
      matching.value = true
      ElMessage.info('已进入匹配队列,等待对手…')
    }
  } catch (e) {
    if (e instanceof ApiError && e.code === 40900) {
      matching.value = true // 已在前一次请求入队,保持排队状态
    } else {
      ElMessage.error(e instanceof Error ? e.message : '匹配失败')
    }
  } finally {
    joining.value = false
  }
}

async function cancelMatch() {
  try {
    await api.cancelMatch()
    matching.value = false
    ElMessage.info('已取消匹配')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

function logout() {
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="lobby">
    <header class="topbar">
      <div class="brand">🍎 Link-Duel</div>
      <div class="user-box">
        <el-tag :type="connected ? 'success' : 'danger'" size="small">
          {{ connected ? '已连接' : '重连中…' }}
        </el-tag>
        <span class="me">{{ auth.user?.nickname }} · {{ auth.user?.points }} 分</span>
        <el-button size="small" plain @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="content">
      <el-card class="match-card">
        <template v-if="!matching">
          <h2>开始匹配</h2>
          <p class="desc">进入队列后将与另一位在线玩家配对,进入同一棋盘限时对战</p>
          <el-button type="primary" size="large" :loading="joining" @click="joinMatch">
            🎮 开始匹配
          </el-button>
        </template>
        <template v-else>
          <h2 class="matching-title">⏳ 匹配中…</h2>
          <p class="desc">正在寻找对手,请稍候</p>
          <el-button size="large" @click="cancelMatch">取消匹配</el-button>
        </template>
      </el-card>

      <LeaderboardPanel />
    </main>
  </div>
</template>

<style scoped>
.lobby {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.brand {
  font-size: 22px;
  font-weight: 700;
}

.user-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

.me {
  color: #606266;
}

.content {
  flex: 1;
  display: flex;
  gap: 24px;
  padding: 24px;
  max-width: 1100px;
  width: 100%;
  margin: 0 auto;
  align-items: flex-start;
}

.match-card {
  flex: 1;
  text-align: center;
  padding: 24px;
}

.match-card h2 {
  margin-bottom: 12px;
}

.matching-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.desc {
  color: #909399;
  margin-bottom: 20px;
}
</style>
