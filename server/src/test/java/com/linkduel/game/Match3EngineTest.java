package com.linkduel.game;

import com.linkduel.dto.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三消引擎单元测试(纯内存,固定随机源保证确定性)。
 * 手工棋盘用字母 A/B/X/Y 作为图案,与 {@link Match3Engine#TILE_EMOJIS} 的
 * 水果互不相同,因此补块永远不会与手工图案意外凑成三连,连锁步数完全可控。
 */
class Match3EngineTest {

    private static final int SIZE = 8;

    /** 确定性随机源:nextInt(bound) 依次返回 0,1,2…(循环),补块永不重样 */
    private static class CyclingRandom extends Random {
        private int i = 0;

        @Override
        public int nextInt(int bound) {
            return (i++) % bound;
        }
    }

    private static Cell cell(int id, String emoji) {
        return new Cell(id, emoji);
    }

    /** 按行字符串构造棋盘,每个字符是一个图案 */
    private static Cell[] board(String... rows) {
        int cols = rows[0].length();
        Cell[] b = new Cell[rows.length * cols];
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < cols; c++) {
                b[r * cols + c] = cell(r * cols + c, String.valueOf(rows[r].charAt(c)));
            }
        }
        return b;
    }

    private static Cell[] copy(Cell[] src) {
        Cell[] out = new Cell[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = cell(src[i].getId(), src[i].getEmoji());
        }
        return out;
    }

    private static void assertBoardEquals(Cell[] expected, Cell[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].getId(), actual[i].getId(), "格子 " + i + " 的 id 被改动");
            assertEquals(expected[i].getEmoji(), actual[i].getEmoji(), "格子 " + i + " 的图案被改动");
        }
    }

    private static List<String> emojisOf(Cell[] board) {
        List<String> list = new ArrayList<>();
        for (Cell c : board) {
            list.add(c.getEmoji());
        }
        list.sort(String::compareTo);
        return list;
    }

    @Test
    void generateProducesFullBoardWithoutInitialMatchesAndSolvable() {
        Cell[] board = Match3Engine.generate(SIZE, 10, new Random(42));
        assertEquals(64, board.length);
        for (int i = 0; i < board.length; i++) {
            assertEquals(i, board[i].getId());
            assertTrue(Match3Engine.TILE_EMOJIS.contains(board[i].getEmoji()));
        }
        assertTrue(Match3Engine.findMatches(board, SIZE).isEmpty(), "初始棋盘不应有三连");
        assertTrue(Match3Engine.hasValidSwap(board, SIZE), "初始棋盘必须存在可行交换");
    }

    @Test
    void findMatchesDetectsHorizontalRun() {
        Cell[] b = board(
                "XYXYXYXY",
                "YXYXYXYX",
                "XYAAAYXY",
                "YXYXYXYX",
                "XYXYXYXY",
                "YXYXYXYX",
                "XYXYXYXY",
                "YXYXYXYX");
        assertEquals(Set.of(18, 19, 20), Match3Engine.findMatches(b, 8));
    }

    @Test
    void findMatchesDetectsVerticalRun() {
        Cell[] b = board(
                "XAX",
                "YAY",
                "XAX");
        assertEquals(Set.of(1, 4, 7), Match3Engine.findMatches(b, 3));
    }

    @Test
    void findMatchesDetectsCrossShape() {
        Cell[] b = board(
                "XAX",
                "AAA",
                "XAX");
        assertEquals(Set.of(1, 3, 4, 5, 7), Match3Engine.findMatches(b, 3));
    }

    @Test
    void resolveSwapRejectsInvalidSwapAndRestoresBoard() {
        Cell[] board = Match3Engine.generate(SIZE, 10, new Random(42));
        Cell[] original = copy(board);
        int[] invalid = findInvalidSwap(board);
        assertNotNull(invalid, "生成的棋盘应存在非法交换");
        Match3Engine.MoveResult result = Match3Engine.resolveSwap(
                board, SIZE, invalid[0], invalid[1], new CyclingRandom());
        assertNull(result);
        assertBoardEquals(original, board); // 非法交换必须还原棋盘
    }

    /** 暴力找第一个"交换后无三连"的相邻对 */
    private int[] findInvalidSwap(Cell[] board) {
        for (int i = 0; i < board.length; i++) {
            int r = i / SIZE;
            int c = i % SIZE;
            if (c + 1 < SIZE && Match3Engine.resolveSwap(
                    copy(board), SIZE, i, i + 1, new CyclingRandom()) == null) {
                return new int[]{i, i + 1};
            }
            if (r + 1 < SIZE && Match3Engine.resolveSwap(
                    copy(board), SIZE, i, i + SIZE, new CyclingRandom()) == null) {
                return new int[]{i, i + SIZE};
            }
        }
        return null;
    }

    @Test
    void resolveSwapCompletesRunAndScores() {
        // 交换 (6,1)↔(7,1) 后第 6 行形成 A,A,A;其余均为交替图案,无连锁
        Cell[] b = board(
                "XYXYXYXY",
                "YXYXYXYX",
                "XYXYXYXY",
                "YXYXYXYX",
                "XYXYXYXY",
                "YXYXYXYX",
                "ABAYXYXY",
                "YAXYXYXY");
        Match3Engine.MoveResult result = Match3Engine.resolveSwap(
                b, SIZE, 49, 57, new CyclingRandom());
        assertNotNull(result);
        assertEquals(1, result.getSteps().size());
        assertEquals(Set.of(48, 49, 50), new HashSet<>(result.getSteps().get(0).getCells()));
        assertEquals(3, result.getScoreGained());
        assertTrue(Match3Engine.findMatches(b, SIZE).isEmpty(), "结算后棋盘不应有三连");
        assertEquals(64, b.length);
    }

    @Test
    void resolveSwapCascadesWithGravityAndRefill() {
        // 交换 (5,1)↔(5,2):第 4-6 行第 2 列形成纵向 A 三连;
        // 消除后 (0,2) 的 A 落到第 3 行,与 (3,0)/(3,1) 的 A 连锁成横向三连。
        // 棋盘基于交错底色(XY 相间)手工构造,除设计的两步外不存在任何其他三连。
        Cell[] b = board(
                "XYAYXYXY",
                "YXYXYXYX",
                "XYXYXYXY",
                "AABYYXYX",
                "XYAXXYXY",
                "YABYYXYX",
                "XYAYXYXY",
                "YXYXYXYX");
        assertTrue(Match3Engine.findMatches(b, SIZE).isEmpty(), "交换前棋盘不应有三连");
        Match3Engine.MoveResult result = Match3Engine.resolveSwap(
                b, SIZE, 41, 42, new CyclingRandom());
        assertNotNull(result);
        assertEquals(2, result.getSteps().size());
        assertEquals(Set.of(34, 42, 50), new HashSet<>(result.getSteps().get(0).getCells()));
        assertEquals(Set.of(24, 25, 26), new HashSet<>(result.getSteps().get(1).getCells()));
        assertEquals(6, result.getScoreGained());
        assertTrue(Match3Engine.findMatches(b, SIZE).isEmpty());

        // 连锁步携带的棋盘快照:下落 + 顶部补块
        Cell[] step1 = result.getSteps().get(0).getBoard();
        assertEquals("A", step1[26].getEmoji());   // (0,2) 的 A 落到第 3 行
        assertEquals("Y", step1[58].getEmoji());   // 列底原方块不动
        assertEquals("🍎", step1[18].getEmoji());  // 顶部补块(确定性随机源)
    }

    @Test
    void adjacencyAndDeadBoardDetection() {
        assertTrue(Match3Engine.isAdjacent(8, 0, 1));
        assertTrue(Match3Engine.isAdjacent(8, 0, 8));
        assertFalse(Match3Engine.isAdjacent(8, 0, 0));
        assertFalse(Match3Engine.isAdjacent(8, 0, 7));
        assertFalse(Match3Engine.isAdjacent(8, 0, 15));

        // 2×2 棋盘任何交换都不可能形成三连 → 死局
        Cell[] tiny = board("XY", "YX");
        assertFalse(Match3Engine.hasValidSwap(tiny, 2));
        assertTrue(Match3Engine.findMatches(tiny, 2).isEmpty());
    }

    @Test
    void reshuffleRestoresSolvabilityAndPreservesTiles() {
        Cell[] board = Match3Engine.generate(SIZE, 10, new Random(7));
        List<String> before = emojisOf(board);
        boolean solved = Match3Engine.reshuffle(board, SIZE, new Random(9), 10);
        assertTrue(solved);
        assertTrue(Match3Engine.hasValidSwap(board, SIZE));
        assertEquals(before, emojisOf(board)); // 只重排,图案集合不变
        for (int i = 0; i < board.length; i++) {
            assertEquals(i, board[i].getId());
        }
    }

    @Test
    void reshuffleReturnsFalseWhenUnsolvable() {
        Cell[] tiny = board("XY", "YX");
        assertFalse(Match3Engine.reshuffle(tiny, 2, new Random(1), 5));
    }
}
