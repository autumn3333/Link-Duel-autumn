<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { serverNow } from '@/api/ws'

/** 倒计时:以 serverNow()(经事件校时)为准,不受本机时钟影响 */
const props = defineProps<{
  deadline: number
  totalMs: number
}>()

const remaining = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function tick() {
  remaining.value = Math.max(0, props.deadline - serverNow())
}

onMounted(() => {
  tick()
  timer = setInterval(tick, 250)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const text = computed(() => {
  const s = Math.ceil(remaining.value / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
})

const percent = computed(() =>
  Math.min(100, Math.max(0, (remaining.value / props.totalMs) * 100)),
)

const danger = computed(() => remaining.value < 10000)
</script>

<template>
  <div class="countdown" :class="{ danger }">
    <span class="clock">⏱</span>
    <div class="bar">
      <el-progress :percentage="percent" :show-text="false" :stroke-width="8" />
    </div>
    <span class="time">{{ text }}</span>
  </div>
</template>

<style scoped>
.countdown {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  max-width: 520px;
}

.bar {
  flex: 1;
}

.time {
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  min-width: 52px;
  text-align: right;
}

.danger .time {
  color: #f56c6c;
}
</style>
