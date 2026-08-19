<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { Tile } from '@/types/game'

/**
 * 8×8 方形棋盘(三消玩法)。
 * 方块以 Tile.key 为稳定身份,TransitionGroup FLIP 负责:
 * 交换/下落 = 同 key 换位置的移动动画,补块 = 新 key 从顶部入场,消除 = dying 消失动画。
 * 交互:按下拖到相邻格松开即交换;原地松开 = 点选(点选两个相邻格也交换)。
 */
defineProps<{
  tiles: Tile[]
  selected: number[]
}>()

const emit = defineEmits<{
  (e: 'cell-click', id: number): void
  (e: 'swap', from: number, to: number): void
}>()

const SIZE = 8
const CELL = 60
const GAP = 6
const PAD = 10

const boardWidth = SIZE * CELL + (SIZE - 1) * GAP + PAD * 2
const boardHeight = boardWidth

const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${SIZE}, ${CELL}px)`,
  // 行高与列宽一致,保证格子是正方形
  gridAutoRows: `${CELL}px`,
  gap: `${GAP}px`,
  padding: `${PAD}px`,
}))

function isAdjacent(a: number, b: number): boolean {
  return (
    Math.abs(Math.floor(a / SIZE) - Math.floor(b / SIZE)) +
      Math.abs((a % SIZE) - (b % SIZE)) ===
    1
  )
}

// ---- 拖动交换(按下 → 划到相邻格 → 松开;原地松开 = 点选) ----
const dragFrom = ref<number | null>(null)
const dragOver = ref<number | null>(null)

function onPointerDown(id: number) {
  dragFrom.value = id
  dragOver.value = null
  window.addEventListener('pointerup', onWindowPointerUp)
}

function onWindowPointerUp() {
  window.removeEventListener('pointerup', onWindowPointerUp)
  const from = dragFrom.value
  const to = dragOver.value
  dragFrom.value = null
  dragOver.value = null
  if (from == null) return
  if (to != null && to !== from && isAdjacent(from, to)) {
    emit('swap', from, to)
  } else if (to == null) {
    emit('cell-click', from)
  }
}

function onPointerEnter(id: number) {
  if (dragFrom.value == null) return
  dragOver.value = isAdjacent(dragFrom.value, id) ? id : null
}

onUnmounted(() => window.removeEventListener('pointerup', onWindowPointerUp))
</script>

<template>
  <div class="board" :style="{ width: `${boardWidth}px`, height: `${boardHeight}px` }">
    <TransitionGroup tag="div" name="cell" class="grid" :style="gridStyle">
      <button
        v-for="tile in tiles"
        :key="tile.key"
        class="cell"
        :class="{
          selected: selected.includes(tile.id),
          dying: tile.dying,
          dragover: dragOver === tile.id,
        }"
        @pointerdown="onPointerDown(tile.id)"
        @pointerenter="onPointerEnter(tile.id)"
      >
        {{ tile.emoji }}
      </button>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.board {
  position: relative;
  background: #1f2937;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
}

.grid {
  display: grid;
}

.cell {
  border: none;
  border-radius: 8px;
  background: #fff;
  font-size: 32px;
  line-height: 1;
  cursor: pointer;
  user-select: none;
  touch-action: none; /* 移动端拖拽不触发页面滚动 */
}

.cell.selected {
  box-shadow: 0 0 0 3px #fbbf24 inset;
}

.cell.dragover {
  box-shadow: 0 0 0 3px #93c5fd inset;
  transform: scale(1.05);
}

.cell.dying {
  animation: die 0.25s ease forwards;
}

@keyframes die {
  to {
    transform: scale(0.2);
    opacity: 0;
  }
}

/* TransitionGroup:交换/下落的 FLIP 移动动画 */
.cell-move {
  transition: transform 0.3s ease;
}

/* 补块:从上方落入 */
.cell-enter-active {
  transition:
    opacity 0.25s ease,
    transform 0.3s ease;
}

.cell-enter-from {
  opacity: 0;
  transform: translateY(-72px) scale(0.6);
}

/* 消除方块已在 dying 动画中播放完毕,离场无需再过渡 */
.cell-leave-active {
  transition: none;
}
</style>
