package com.linkduel.game;

import com.linkduel.dto.Cell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardGeneratorTest {

    private static final int SIZE = 8;

    @Test
    @DisplayName("生成的初始棋盘:64 格、每种图案 8 个、全部未消除、至少有一个可消除对")
    void generateBoardInvariants() {
        Cell[] board = BoardGenerator.generate(SIZE, 10);

        assertEquals(64, board.length);
        Map<String, Integer> counts = new HashMap<>();
        for (Cell cell : board) {
            assertFalse(cell.isEliminated(), "初始棋盘不应有已消除格子");
            counts.merge(cell.getEmoji(), 1, Integer::sum);
        }
        assertEquals(8, counts.size(), "应有 8 种图案");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertEquals(8, entry.getValue(), "每种图案应出现 8 次(4 对)");
        }
        assertTrue(BoardGenerator.hasLegalMove(board, SIZE), "初始棋盘必须可玩");
    }

    @Test
    @DisplayName("多次生成:每种图案成对出现,可玩性稳定")
    void generateManyTimes() {
        for (int i = 0; i < 50; i++) {
            Cell[] board = BoardGenerator.generate(SIZE, 10);
            Map<String, Integer> counts = new HashMap<>();
            for (Cell cell : board) {
                counts.merge(cell.getEmoji(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                assertEquals(0, entry.getValue() % 2, "图案必须成对出现");
            }
            assertTrue(BoardGenerator.hasLegalMove(board, SIZE));
        }
    }

    @Test
    @DisplayName("洗牌剩余:已消除位置保持不变,剩余图案多重集不变,洗后有解")
    void reshuffleRemainingPreservesState() {
        Cell[] board = BoardGenerator.generate(SIZE, 10);
        Set<Integer> eliminatedIds = Set.of(0, 5, 9, 17, 63, 40);
        Map<String, Integer> before = new HashMap<>();
        for (Cell cell : board) {
            if (eliminatedIds.contains(cell.getId())) {
                cell.setEliminated(true);
            } else {
                before.merge(cell.getEmoji(), 1, Integer::sum);
            }
        }

        boolean solved = BoardGenerator.reshuffleRemaining(board, SIZE, 10);
        assertTrue(solved, "消除 6 格后洗牌必然能找到解");

        Map<String, Integer> after = new HashMap<>();
        for (Cell cell : board) {
            if (eliminatedIds.contains(cell.getId())) {
                assertTrue(cell.isEliminated(), "已消除位置必须保持消除状态");
            } else {
                assertFalse(cell.isEliminated());
                after.merge(cell.getEmoji(), 1, Integer::sum);
            }
        }
        assertEquals(before, after, "剩余图案的多重集必须不变");
        assertTrue(BoardGenerator.hasLegalMove(board, SIZE));
    }
}
