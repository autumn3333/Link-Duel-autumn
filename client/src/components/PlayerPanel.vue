<script setup lang="ts">
import type { PlayerInfo } from '@/types/game'

defineProps<{
  player: PlayerInfo
  score: number
  me: boolean
}>()
</script>

<template>
  <div class="player-panel" :class="{ me }">
    <div class="avatar">🧑‍🚀</div>
    <div class="info">
      <div class="name">
        {{ player.nickname }}
        <el-tag v-if="me" size="small" type="primary">我</el-tag>
      </div>
      <div class="status">
        <span class="dot" :class="player.online ? 'on' : 'off'" />
        {{ player.online ? '在线' : '离线' }}
      </div>
    </div>
    <div class="score">
      <span class="num">{{ score }}</span>
      <span class="unit">对</span>
    </div>
  </div>
</template>

<style scoped>
.player-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-radius: 12px;
  padding: 10px 16px;
  min-width: 240px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.player-panel.me {
  border: 2px solid #409eff;
}

.avatar {
  font-size: 34px;
}

.name {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.status {
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  gap: 4px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.dot.on {
  background: #67c23a;
}

.dot.off {
  background: #f56c6c;
}

.score {
  margin-left: auto;
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.score .num {
  font-size: 26px;
  font-weight: 700;
  color: #409eff;
}

.score .unit {
  font-size: 12px;
  color: #909399;
}
</style>
