import type { CellState } from '@/types/game'

/**
 * ≤2 转弯路径校验 —— 与后端 PathValidator 完全一致的 TS 镜像实现,
 * 仅供前端做点击预判(悬停高亮、即时提示)。最终合法性以服务端为准
 * (服务端用同一算法对 Redis 中的权威棋盘重新校验)。
 * cell id = row * size + col;已消除的格子视为"空位,可穿过";禁止绕边。
 */
export function findPath(board: CellState[], size: number, a: number, b: number): number[] | null {
  if (a === b) return null
  if (a < 0 || b < 0 || a >= board.length || b >= board.length) return null
  const ca = board[a]
  const cb = board[b]
  if (!ca || !cb || ca.eliminated || cb.eliminated) return null
  if (ca.emoji !== cb.emoji) return null

  const rowA = Math.floor(a / size)
  const colA = a % size
  const rowB = Math.floor(b / size)
  const colB = b % size

  // 0 转弯:同行或同列且中间全空
  if (isClear(board, size, rowA, colA, rowB, colB)) {
    return [a, b]
  }

  // 1 转弯(L 形):两个候选拐角
  if (isEmpty(board, size, rowA, colB)
    && isClear(board, size, rowA, colA, rowA, colB)
    && isClear(board, size, rowA, colB, rowB, colB)) {
    return [a, rowA * size + colB, b]
  }
  if (isEmpty(board, size, rowB, colA)
    && isClear(board, size, rowA, colA, rowB, colA)
    && isClear(board, size, rowB, colA, rowB, colB)) {
    return [a, rowB * size + colA, b]
  }

  // 2 转弯(Z/S 形):水平扫描枢轴 (r, colA)/(r, colB),再垂直扫描(不绕边)
  for (let r = 0; r < size; r++) {
    if (isEmpty(board, size, r, colA) && isEmpty(board, size, r, colB)
      && isClear(board, size, rowA, colA, r, colA)
      && isClear(board, size, r, colA, r, colB)
      && isClear(board, size, r, colB, rowB, colB)) {
      return [a, r * size + colA, r * size + colB, b]
    }
  }
  for (let c = 0; c < size; c++) {
    if (isEmpty(board, size, rowA, c) && isEmpty(board, size, rowB, c)
      && isClear(board, size, rowA, colA, rowA, c)
      && isClear(board, size, rowA, c, rowB, c)
      && isClear(board, size, rowB, c, rowB, colB)) {
      return [a, rowA * size + c, rowB * size + c, b]
    }
  }
  return null
}

/** 该格子是否已消除(视为可穿过的空位) */
function isEmpty(board: CellState[], size: number, row: number, col: number): boolean {
  return board[row * size + col].eliminated
}

/** 同行或同列,且两端之间(不含端点)所有格子均已消除;相邻 = 中间 0 格 = 必然通 */
function isClear(board: CellState[], size: number, r1: number, c1: number, r2: number, c2: number): boolean {
  if (r1 === r2) {
    const min = Math.min(c1, c2)
    const max = Math.max(c1, c2)
    for (let c = min + 1; c < max; c++) {
      if (!isEmpty(board, size, r1, c)) return false
    }
    return true
  }
  if (c1 === c2) {
    const min = Math.min(r1, r2)
    const max = Math.max(r1, r2)
    for (let r = min + 1; r < max; r++) {
      if (!isEmpty(board, size, r, c1)) return false
    }
    return true
  }
  return false
}
