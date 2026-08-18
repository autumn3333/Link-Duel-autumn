package com.linkduel.game;

import com.linkduel.dto.Cell;

import java.util.ArrayList;
import java.util.List;

/**
 * 连连看核心算法:两个格子"图案相同且连线转弯不超过 2 次"才可消除。
 *
 * <p>规则(标准连连看):
 * <ul>
 *   <li>0 转弯:两点同行或同列,中间格子全部已消除;</li>
 *   <li>1 转弯(L 形):拐角格已消除,且两条直角边畅通;</li>
 *   <li>2 转弯(Z/S 形):两个枢轴格已消除,且三段畅通。</li>
 * </ul>
 *
 * <p>路径只能在棋盘内部,不绕边、不取模。本类为纯静态实现,服务端权威校验用;
 * 前端另有一份 TypeScript 镜像实现,仅用于用户体验预判(见 DESIGN.md 取舍说明)。
 */
public final class PathValidator {

    private PathValidator() {
    }

    /**
     * 判断 a、b 两格是否可消除。
     *
     * @return 可消除时返回经过的格子 id 序列(含两端点,按路径顺序),供前端绘制连线;
     *         不可消除返回 null
     */
    public static List<Integer> findPath(Cell[] board, int size, int a, int b) {
        // 前置校验:坐标合法、非同一格、两端未消除、图案相同
        if (a == b || a < 0 || b < 0 || a >= board.length || b >= board.length) {
            return null;
        }
        Cell cellA = board[a];
        Cell cellB = board[b];
        if (cellA.isEliminated() || cellB.isEliminated()) {
            return null;
        }
        if (!cellA.getEmoji().equals(cellB.getEmoji())) {
            return null;
        }

        int rowA = a / size, colA = a % size;
        int rowB = b / size, colB = b % size;

        // 0 转弯:直接连通
        if (isClear(board, size, rowA, colA, rowB, colB)) {
            List<Integer> path = new ArrayList<>();
            path.add(a);
            path.add(b);
            return path;
        }

        // 1 转弯:两个候选拐角
        for (int[] corner : new int[][]{{rowA, colB}, {rowB, colA}}) {
            int cr = corner[0], cc = corner[1];
            int cornerId = cr * size + cc;
            if (board[cornerId].isEliminated()
                    && isClear(board, size, rowA, colA, cr, cc)
                    && isClear(board, size, cr, cc, rowB, colB)) {
                List<Integer> path = new ArrayList<>();
                path.add(a);
                path.add(cornerId);
                path.add(b);
                return path;
            }
        }

        // 2 转弯:水平扫描(枢轴在同一行 r 上,分别与 a、b 同列)
        for (int r = 0; r < size; r++) {
            int p1 = r * size + colA;
            int p2 = r * size + colB;
            if (board[p1].isEliminated() && board[p2].isEliminated()
                    && isClear(board, size, rowA, colA, r, colA)
                    && isClear(board, size, r, colA, r, colB)
                    && isClear(board, size, r, colB, rowB, colB)) {
                List<Integer> path = new ArrayList<>();
                path.add(a);
                path.add(p1);
                path.add(p2);
                path.add(b);
                return path;
            }
        }

        // 2 转弯:垂直扫描(枢轴在同一列 c 上,分别与 a、b 同行)
        for (int c = 0; c < size; c++) {
            int p1 = rowA * size + c;
            int p2 = rowB * size + c;
            if (board[p1].isEliminated() && board[p2].isEliminated()
                    && isClear(board, size, rowA, colA, rowA, c)
                    && isClear(board, size, rowA, c, rowB, c)
                    && isClear(board, size, rowB, c, rowB, colB)) {
                List<Integer> path = new ArrayList<>();
                path.add(a);
                path.add(p1);
                path.add(p2);
                path.add(b);
                return path;
            }
        }

        return null;
    }

    /**
     * 两点同行或同列,且严格位于两点之间的格子全部已消除(相邻则中间无格子,必然畅通)。
     */
    private static boolean isClear(Cell[] board, int size, int r1, int c1, int r2, int c2) {
        if (r1 == r2) {
            int from = Math.min(c1, c2), to = Math.max(c1, c2);
            for (int c = from + 1; c < to; c++) {
                if (!board[r1 * size + c].isEliminated()) {
                    return false;
                }
            }
            return true;
        }
        if (c1 == c2) {
            int from = Math.min(r1, r2), to = Math.max(r1, r2);
            for (int r = from + 1; r < to; r++) {
                if (!board[r * size + c1].isEliminated()) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
