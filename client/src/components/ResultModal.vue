<script setup lang="ts">
import { computed } from 'vue'
import type { GameOverData, PlayerInfo } from '@/types/game'
import { REASON_LABEL, STATUS_LABEL } from '@/utils/constants'

/** 对局结果弹窗:胜负判定、结算原因、积分变化 */
const props = defineProps<{
  over: GameOverData
  meId: number
  players: { a: PlayerInfo; b: PlayerInfo }
}>()

const emit = defineEmits<{ (e: 'back'): void }>()

const iAmA = computed(() => props.players.a.id === props.meId)
const myScore = computed(() => (iAmA.value ? props.over.scoreA : props.over.scoreB))
const oppScore = computed(() => (iAmA.value ? props.over.scoreB : props.over.scoreA))
const oppName = computed(() =>
  iAmA.value ? props.players.b.nickname : props.players.a.nickname,
)
const myDelta = computed(() => (iAmA.value ? props.over.deltaA : props.over.deltaB) ?? 0)
const oppDelta = computed(() => (iAmA.value ? props.over.deltaB : props.over.deltaA) ?? 0)

const title = computed(() => {
  if (props.over.status === 'cancelled') return '对局已取消'
  if (props.over.winnerId === null) return '平局 🤝'
  return props.over.winnerId === props.meId ? '胜利!🏆' : '惜败 💪'
})

const reasonText = computed(() => REASON_LABEL[props.over.reason] ?? props.over.reason)
</script>

<template>
  <el-dialog
    :model-value="true"
    width="420"
    :show-close="false"
    :close-on-click-modal="false"
    align-center
  >
    <div class="result">
      <h2 class="title">{{ title }}</h2>
      <p class="reason">{{ reasonText }}({{ STATUS_LABEL[over.status] }})</p>
      <div class="scores">
        <div class="side">
          <div class="label">我</div>
          <div class="num">{{ myScore }}</div>
          <div v-if="myDelta > 0" class="delta">+{{ myDelta }} 分</div>
        </div>
        <div class="vs">VS</div>
        <div class="side">
          <div class="label">{{ oppName }}</div>
          <div class="num">{{ oppScore }}</div>
          <div v-if="oppDelta > 0" class="delta">+{{ oppDelta }} 分</div>
        </div>
      </div>
      <el-button type="primary" size="large" class="back" @click="emit('back')">
        返回大厅
      </el-button>
    </div>
  </el-dialog>
</template>

<style scoped>
.result {
  text-align: center;
}

.title {
  font-size: 28px;
  margin-bottom: 4px;
}

.reason {
  color: #909399;
  margin-bottom: 18px;
}

.scores {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 28px;
  margin-bottom: 22px;
}

.side .label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 4px;
}

.side .num {
  font-size: 34px;
  font-weight: 700;
  color: #409eff;
}

.side .delta {
  color: #67c23a;
  font-size: 13px;
}

.vs {
  color: #c0c4cc;
  font-weight: 700;
}

.back {
  width: 100%;
}
</style>
