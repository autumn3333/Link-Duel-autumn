package com.linkduel.game;

import com.linkduel.dto.Cell;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 三消引擎(纯静态、服务端权威,开心消消乐式玩法):
 * <ul>
 *   <li>棋盘 size×size 全满(无"已消除"概念),图案取 {@link #TILE_EMOJIS};</li>
 *   <li>一步操作 = 交换两个正交相邻格;交换后必须形成至少一处横/竖 ≥3 连,否则非法;</li>
 *   <li>合法交换 → 消除所有三连(支持十字交叉)→ 按列下落 → 顶部随机补块 → 循环连锁;</li>
 *   <li>初始棋盘保证无三连且至少存在一个可行交换;死局可无限次自动洗牌(调用方决定)。</li>
 * </ul>
 * 所有随机行为都显式接收 {@link Random},保证单元测试可复现。
 */
public final class Match3Engine {

    /** 6 种水果图案(图案种类越少三连密度越高,更贴近消消乐节奏) */
    public static final List<String> TILE_EMOJIS = List.of(
            "🍎", "🍌", "🍇", "🍓", "🍊", "🍉");

    private Match3Engine() {
    }

    /** 连锁中的一步:消除了哪些格子 + 消除并下落补块后的完整棋盘 */
    public static class Step {
        private final List<Integer> cells;
        private final Cell[] board;

        Step(List<Integer> cells, Cell[] board) {
            this.cells = cells;
            this.board = board;
        }

        public List<Integer> getCells() {
            return cells;
        }

        public Cell[] getBoard() {
            return board;
        }
    }

    /** 一次交换的完整结算结果 */
    public static class MoveResult {
        private final List<Step> steps;
        private final int scoreGained;

        MoveResult(List<Step> steps, int scoreGained) {
            this.steps = steps;
            this.scoreGained = scoreGained;
        }

        public List<Step> getSteps() {
            return steps;
        }

        /** 总消除格数 = 本次操作的得分 */
        public int getScoreGained() {
            return scoreGained;
        }
    }

    /** 生成初始棋盘:无初始三连 + 至少一个可行交换;maxTries 次随机重试后兜底返回 */
    public static Cell[] generate(int size, int maxTries, Random random) {
        for (int attempt = 0; attempt < maxTries; attempt++) {
            Cell[] board = fillWithoutInitialMatch(size, random);
            if (hasValidSwap(board, size)) {
                return board;
            }
        }
        return fillWithoutInitialMatch(size, random);
    }

    /** 逐格填充,每格图案保证不与左侧/上方形成三连(初始无匹配) */
    private static Cell[] fillWithoutInitialMatch(int size, Random random) {
        int cells = size * size;
        Cell[] board = new Cell[cells];
        for (int id = 0; id < cells; id++) {
            String emoji = TILE_EMOJIS.get(random.nextInt(TILE_EMOJIS.size()));
            for (int tries = 0; tries < 10 && createsRun(board, size, id, emoji); tries++) {
                emoji = TILE_EMOJIS.get(random.nextInt(TILE_EMOJIS.size()));
            }
            board[id] = new Cell(id, emoji);
        }
        return board;
    }

    private static boolean createsRun(Cell[] board, int size, int id, String emoji) {
        int r = id / size;
        int c = id % size;
        if (c >= 2 && emoji.equals(board[id - 1].getEmoji())
                && emoji.equals(board[id - 2].getEmoji())) {
            return true;
        }
        return r >= 2 && emoji.equals(board[id - size].getEmoji())
                && emoji.equals(board[id - size * 2].getEmoji());
    }

    /** 两格是否正交相邻 */
    public static boolean isAdjacent(int size, int a, int b) {
        return Math.abs(a / size - b / size) + Math.abs(a % size - b % size) == 1;
    }

    /** 找出当前棋盘所有 ≥3 连的格子集合(横竖 runs 的并集,支持十字/丁字交叉) */
    public static Set<Integer> findMatches(Cell[] board, int size) {
        Set<Integer> matched = new HashSet<>();
        for (int r = 0; r < size; r++) {
            int runStart = 0;
            for (int c = 1; c <= size; c++) {
                boolean same = c < size && board[r * size + c - 1].getEmoji()
                        .equals(board[r * size + c].getEmoji());
                if (!same) {
                    if (c - runStart >= 3) {
                        for (int i = runStart; i < c; i++) {
                            matched.add(r * size + i);
                        }
                    }
                    runStart = c;
                }
            }
        }
        for (int c = 0; c < size; c++) {
            int runStart = 0;
            for (int r = 1; r <= size; r++) {
                boolean same = r < size && board[(r - 1) * size + c].getEmoji()
                        .equals(board[r * size + c].getEmoji());
                if (!same) {
                    if (r - runStart >= 3) {
                        for (int i = runStart; i < r; i++) {
                            matched.add(i * size + c);
                        }
                    }
                    runStart = r;
                }
            }
        }
        return matched;
    }

    /** 棋盘是否存在至少一个可行交换(死局判断) */
    public static boolean hasValidSwap(Cell[] board, int size) {
        for (int id = 0; id < board.length; id++) {
            // 只试右邻与下邻,即可覆盖全部相邻对
            int r = id / size;
            int c = id % size;
            if (c + 1 < size && swapCreatesMatch(board, size, id, id + 1)) {
                return true;
            }
            if (r + 1 < size && swapCreatesMatch(board, size, id, id + size)) {
                return true;
            }
        }
        return false;
    }

    private static boolean swapCreatesMatch(Cell[] board, int size, int a, int b) {
        swapEmoji(board, a, b);
        boolean hasMatch = !findMatches(board, size).isEmpty();
        swapEmoji(board, a, b);
        return hasMatch;
    }

    /**
     * 执行一次交换并结算连锁。
     *
     * @return 非法交换(交换后无三连)返回 null,且棋盘还原;
     *         合法返回各连锁步 + 总消除格数(棋盘已就地更新为最终状态)
     */
    public static MoveResult resolveSwap(Cell[] board, int size, int a, int b, Random random) {
        swapEmoji(board, a, b);
        List<Step> steps = new ArrayList<>();
        int total = 0;
        while (true) {
            Set<Integer> matched = findMatches(board, size);
            if (matched.isEmpty()) {
                break;
            }
            List<Integer> cells = matched.stream().sorted().toList();
            total += cells.size();
            applyGravity(board, size, matched, random);
            steps.add(new Step(cells, copyBoard(board)));
        }
        if (steps.isEmpty()) {
            swapEmoji(board, a, b);
            return null;
        }
        return new MoveResult(steps, total);
    }

    /** 消除 → 按列下落 → 顶部随机补块(补块可能立即形成新三连,交给外层循环连锁) */
    private static void applyGravity(Cell[] board, int size, Set<Integer> removed, Random random) {
        for (int c = 0; c < size; c++) {
            Deque<String> kept = new ArrayDeque<>();
            for (int r = size - 1; r >= 0; r--) {
                int id = r * size + c;
                if (!removed.contains(id)) {
                    kept.addLast(board[id].getEmoji());
                }
            }
            for (int r = size - 1; r >= 0; r--) {
                String emoji = kept.isEmpty()
                        ? TILE_EMOJIS.get(random.nextInt(TILE_EMOJIS.size()))
                        : kept.removeFirst();
                board[r * size + c].setEmoji(emoji);
            }
        }
    }

    /** 死局洗牌:重排全部图案直到出现可行交换;maxTries 次后返回 false(棋盘保留最后一次结果) */
    public static boolean reshuffle(Cell[] board, int size, Random random, int maxTries) {
        List<String> emojis = new ArrayList<>();
        for (Cell cell : board) {
            emojis.add(cell.getEmoji());
        }
        for (int attempt = 0; attempt < maxTries; attempt++) {
            Collections.shuffle(emojis, random);
            for (int i = 0; i < board.length; i++) {
                board[i].setEmoji(emojis.get(i));
            }
            if (hasValidSwap(board, size)) {
                return true;
            }
        }
        return false;
    }

    private static void swapEmoji(Cell[] board, int a, int b) {
        String tmp = board[a].getEmoji();
        board[a].setEmoji(board[b].getEmoji());
        board[b].setEmoji(tmp);
    }

    private static Cell[] copyBoard(Cell[] board) {
        Cell[] copy = new Cell[board.length];
        for (int i = 0; i < board.length; i++) {
            copy[i] = new Cell(board[i].getId(), board[i].getEmoji());
        }
        return copy;
    }
}
