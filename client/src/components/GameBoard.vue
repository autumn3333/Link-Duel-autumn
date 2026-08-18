<script setup lang="ts">
import { computed } from 'vue'
import type { CellState } from '@/types/game'

/**
 * 8×8 棋盘 + 消除路径动画层。
 * 布局参数在此统一维护,SVG 折线沿"服务端返回的实际路径"的格子中心绘制。
 */
const props = defineProps<{
  board: CellState[]
  selected: number[]
  /** 正在播放的消除动画(来自服务端 eliminated 事件) */
  elimination: { path: number[]; emoji: string } | null
}>()

const emit = defineEmits<{ (e: 'cell-click', id: number): void }>()

const SIZE = 8
const CELL = 60
const GAP = 6
const PAD = 10

const boardWidth = SIZE * CELL + (SIZE - 1) * GAP + PAD * 2
const boardHeight = boardWidth

const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${SIZE}, ${CELL}px)`,
  gap: `${GAP}px`,
  padding: `${PAD}px`,
}))

function center(id: number): { x: number; y: number } {
  const row = Math.floor(id / SIZE)
  const col = id % SIZE
  return {
    x: PAD + col * (CELL + GAP) + CELL / 2,
    y: PAD + row * (CELL + GAP) + CELL / 2,
  }
}

const linePoints = computed(() =>
  (props.elimination?.path ?? [])
    .map((id) => {
      const p = center(id)
      return `${p.x},${p.y}`
    })
    .join(' '),
)

function cellClass(cell: CellState, id: number) {
  const path = props.elimination?.path
  return {
    dead: cell.eliminated,
    selected: props.selected.includes(id),
    dying: path != null && (path[0] === id || path[path.length - 1] === id),
  }
}
</script>

<template>
  <div class="board" :style="{ width: `${boardWidth}px`, height: `${boardHeight}px` }">
    <div class="grid" :style="gridStyle">
      <button
        v-for="cell in board"
        :key="cell.id"
        class="cell"
        :class="cellClass(cell, cell.id)"
        :disabled="cell.eliminated"
        @click="emit('cell-click', cell.id)"
      >
        {{ cell.emoji }}
      </button>
    </div>
    <svg v-if="elimination" class="overlay" :width="boardWidth" :height="boardHeight">
      <polyline :points="linePoints" class="line" />
    </svg>
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
  transition:
    transform 0.1s,
    box-shadow 0.1s,
    background 0.1s;
}

.cell:hover:not(:disabled) {
  transform: scale(1.06);
}

.cell.selected {
  box-shadow: 0 0 0 3px #fbbf24 inset;
  transform: scale(1.05);
}

.cell.dead {
  visibility: hidden;
}

.cell.dying {
  animation: die 0.6s ease forwards;
}

@keyframes die {
  to {
    transform: scale(0.2);
    opacity: 0;
  }
}

.overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.line {
  fill: none;
  stroke: #ff6b6b;
  stroke-width: 4;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 1000;
  stroke-dashoffset: 1000;
  animation: draw 0.6s linear forwards;
}

@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}
</style>
