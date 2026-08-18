<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/http'
import type { LeaderboardItem } from '@/types/game'

const list = ref<LeaderboardItem[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await api.leaderboard(10)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '排行榜加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

defineExpose({ load })

function rowClassName({ row }: { row: LeaderboardItem }) {
  return row.isMe ? 'me-row' : ''
}
</script>

<template>
  <el-card class="leaderboard">
    <template #header>
      <div class="header">
        <span>🏆 实时排行榜</span>
        <el-button size="small" text type="primary" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table :data="list" v-loading="loading" size="small" :row-class-name="rowClassName">
      <el-table-column prop="rank" label="排名" width="70" />
      <el-table-column prop="nickname" label="玩家" />
      <el-table-column prop="points" label="积分" width="90" />
    </el-table>
    <p class="tip">积分实时维护在 Redis ZSET,结算后自动更新</p>
  </el-card>
</template>

<style scoped>
.leaderboard {
  width: 380px;
  flex-shrink: 0;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tip {
  margin-top: 10px;
  font-size: 12px;
  color: #a8abb2;
  text-align: center;
}

:deep(.me-row) {
  background: #ecf5ff;
  font-weight: 600;
}
</style>
