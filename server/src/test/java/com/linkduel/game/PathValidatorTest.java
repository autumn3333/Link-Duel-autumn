package com.linkduel.game;

import com.linkduel.dto.Cell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 核心算法测试:≤2 转弯路径校验。
 * 棋盘构造约定:默认全空(已消除),用 place() 放置图案与障碍。
 */
class PathValidatorTest {

    private static final int SIZE = 8;
    private static final String APPLE = "🍎";

    // ---------- 工具 ----------

    private static Cell[] emptyBoard() {
        Cell[] board = new Cell[SIZE * SIZE];
        for (int i = 0; i < board.length; i++) {
            board[i] = new Cell(i, ".", true);
        }
        return board;
    }

    /** 放置一个未消除的图案(或障碍) */
    private static void place(Cell[] board, int id, String emoji) {
        board[id].setEmoji(emoji);
        board[id].setEliminated(false);
    }

    private static List<Integer> path(Cell[] board, int a, int b) {
        return PathValidator.findPath(board, SIZE, a, b);
    }

    // ---------- 0 转弯 ----------

    @Test
    @DisplayName("0 转弯:同一行相邻,直接连通")
    void directAdjacentSameRow() {
        Cell[] board = emptyBoard();
        place(board, 8, APPLE);
        place(board, 9, APPLE);
        assertNotNull(path(board, 8, 9));
    }

    @Test
    @DisplayName("0 转弯:同一行,中间均为空位")
    void directSameRowWithEmptyBetween() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);
        place(board, 5, APPLE);
        assertNotNull(path(board, 0, 5));
    }

    @Test
    @DisplayName("0 转弯:同一行,中间有障碍时可经其他行绕行")
    void directSameRowBlockedDetourExists() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);
        place(board, 5, APPLE);
        place(board, 3, "🍌");
        List<Integer> path = path(board, 0, 5);
        // 正确实现应找到 2 转弯绕行路径 [0, 8, 13, 5]
        assertNotNull(path);
        assertEquals(4, path.size());
    }

    @Test
    @DisplayName("0 转弯:同一行,中间有障碍且绕行通道被完全封死")
    void directSameRowBlockedAndSealed() {
        Cell[] board = emptyBoard();
        place(board, 1, APPLE);   // (0,1)
        place(board, 5, APPLE);   // (0,5)
        place(board, 3, "🍌");    // (0,3) 挡直连
        for (int c = 0; c < SIZE; c++) {  // 第 1 行占满,封死所有绕行通道
            place(board, 8 + c, "🍌");
        }
        assertNull(path(board, 1, 5));
    }

    @Test
    @DisplayName("0 转弯:同一列,中间均为空位")
    void directSameColumnWithEmptyBetween() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);
        place(board, 16, APPLE);
        assertNotNull(path(board, 0, 16));
    }

    @Test
    @DisplayName("0 转弯:同一列,中间有障碍且绕行通道被完全封死")
    void directSameColumnBlockedAndSealed() {
        Cell[] board = emptyBoard();
        place(board, 1, APPLE);   // (0,1)
        place(board, 17, APPLE);  // (2,1)
        place(board, 9, "🍌");    // (1,1) 挡直连
        // 封死 (0,1) 所在行的其余格子,拐角与垂直绕行全部失效
        for (int c = 0; c < SIZE; c++) {
            if (c != 1) {
                place(board, c, "🍌");
            }
        }
        assertNull(path(board, 1, 17));
    }

    @Test
    @DisplayName("边界:同行两端 (0,0)-(0,7) 全空可连")
    void boundarySameRowFullSpan() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);
        place(board, 7, APPLE);
        assertNotNull(path(board, 0, 7));
    }

    // ---------- 1 转弯 ----------

    @Test
    @DisplayName("1 转弯:标准 L 形有效")
    void oneTurnValid() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 17, APPLE);  // (2,1)
        // 拐角 (0,1) 为空,两臂畅通
        assertNotNull(path(board, 0, 17));
    }

    @Test
    @DisplayName("1 转弯:两条候选臂都被挡")
    void oneTurnBlocked() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 17, APPLE);  // (2,1)
        place(board, 9, "🍌");    // (1,1) 挡住拐角 (0,1) 到 (2,1) 的臂
        place(board, 1, "🍌");    // (0,1) 挡住拐角 (0,1)
        place(board, 8, "🍌");    // (1,0) 挡住拐角 (2,0) 到 (0,0) 的臂
        place(board, 16, "🍌");   // (2,0) 挡住拐角 (2,0)
        assertNull(path(board, 0, 17));
    }

    @Test
    @DisplayName("1 转弯:第一个拐角被占,走第二个拐角仍可连")
    void oneTurnSecondCorner() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 17, APPLE);  // (2,1)
        place(board, 1, "🍌");    // (0,1) 拐角一被占
        // 拐角二 (2,0) 为空,(1,0) 为空,可连
        assertNotNull(path(board, 0, 17));
    }

    // ---------- 2 转弯 ----------

    @Test
    @DisplayName("2 转弯:水平 Z 形有效")
    void twoTurnHorizontalValid() {
        Cell[] board = emptyBoard();
        place(board, 1, APPLE);   // (0,1)
        place(board, 19, APPLE);  // (2,3)
        // 枢轴 (1,1)、(1,3) 及其中间 (1,2) 均为空
        assertNotNull(path(board, 1, 19));
    }

    @Test
    @DisplayName("2 转弯:垂直 S 形有效")
    void twoTurnVerticalValid() {
        Cell[] board = emptyBoard();
        place(board, 8, APPLE);   // (1,0)
        place(board, 19, APPLE);  // (2,3)
        // 枢轴 (1,3)、(2,0) 为空,中间 (2,1)、(2,2) 为空
        assertNotNull(path(board, 8, 19));
    }

    @Test
    @DisplayName("2 转弯:水平 Z 形被挡且所有绕行被封死")
    void twoTurnHorizontalBlocked() {
        Cell[] board = emptyBoard();
        place(board, 1, APPLE);   // (0,1)
        place(board, 19, APPLE);  // (2,3)
        place(board, 10, "🍌");   // (1,2) 挡水平 Z 通道
        place(board, 3, "🍌");    // (0,3) 挡拐角一
        place(board, 17, "🍌");   // (2,1) 挡拐角二 + 封死下方 Z 枢轴列
        for (int c = 0; c < SIZE; c++) {  // 第 3 行占满,封死长 Z 绕行
            place(board, 24 + c, "🍌");
        }
        assertNull(path(board, 1, 19));
    }

    @Test
    @DisplayName("2 转弯:垂直 S 形被挡且所有绕行被封死")
    void twoTurnVerticalBlocked() {
        Cell[] board = emptyBoard();
        place(board, 8, APPLE);   // (1,0)
        place(board, 19, APPLE);  // (2,3)
        place(board, 18, "🍌");   // (2,2) 挡垂直 S 通道
        place(board, 11, "🍌");   // (1,3) 挡拐角一 + 封死右侧垂直枢轴行
        place(board, 16, "🍌");   // (2,0) 挡拐角二 + 封死下方水平枢轴列
        assertNull(path(board, 8, 19));
    }

    // ---------- 非法输入 ----------

    @Test
    @DisplayName("同一个格子不可消除")
    void sameCellInvalid() {
        Cell[] board = emptyBoard();
        place(board, 5, APPLE);
        assertNull(path(board, 5, 5));
    }

    @Test
    @DisplayName("图案不同不可消除")
    void differentEmojiInvalid() {
        Cell[] board = emptyBoard();
        place(board, 8, APPLE);
        place(board, 9, "🍌");
        assertNull(path(board, 8, 9));
    }

    @Test
    @DisplayName("端点已被消除则不可选")
    void eliminatedEndpointInvalid() {
        Cell[] board = emptyBoard();
        place(board, 8, APPLE);
        board[9].setEmoji(APPLE);
        board[9].setEliminated(true);
        assertNull(path(board, 8, 9));
    }

    @Test
    @DisplayName("坐标越界不可消除")
    void outOfRangeInvalid() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);
        assertNull(path(board, 0, 64));
        assertNull(path(board, -1, 0));
    }

    // ---------- 绕边禁止与复杂情形 ----------

    @Test
    @DisplayName("禁止绕边:棋盘内无 ≤2 转弯路径时,即使绕棋盘外圈可连,也判不可连")
    void wrapAroundForbidden() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 63, APPLE);  // (7,7)
        // 占满第 1~6 行,使棋盘内所有通道被堵死
        for (int r = 1; r <= 6; r++) {
            for (int c = 0; c < SIZE; c++) {
                place(board, r * SIZE + c, "🍌");
            }
        }
        // 绕边实现(把棋盘外一圈当作可通行)会误判可连;正确实现应返回不可连
        assertNull(path(board, 0, 63));
    }

    @Test
    @DisplayName("需要 3 次及以上转弯的情形不可消除")
    void moreThanTwoTurnsInvalid() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 2, APPLE);   // (0,2)
        place(board, 1, "🍌");    // (0,1) 挡住直连
        place(board, 9, "🍌");    // (1,1) 挡住水平 Z
        place(board, 10, "🍌");   // (1,2) 挡住垂直 Z
        assertNull(path(board, 0, 2));
    }

    @Test
    @DisplayName("路径只允许在棋盘内部:靠边两点的 L 形沿边可连")
    void edgeLShapeValid() {
        Cell[] board = emptyBoard();
        place(board, 0, APPLE);   // (0,0)
        place(board, 8, APPLE);   // (1,0) 相邻可连
        assertEquals(2, path(board, 0, 8).size());
        // 另验证沿边的 L 形:(0,7) 与 (1,0),拐角 (0,0) 为空
        place(board, 7, "🍓");
        place(board, 8, "🍓");
        board[0].setEliminated(true);
        board[0].setEmoji(".");
        List<Integer> p = path(board, 7, 8);
        assertNotNull(p);
        assertEquals(3, p.size());
        assertEquals(Integer.valueOf(0), p.get(1)); // 拐角是 (0,0)
    }
}
