package com.linkduel.game;

import com.linkduel.dto.Cell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 棋盘生成与洗牌(纯逻辑,不依赖 Redis)。
 *
 * <p>初始棋盘:8 种 Emoji 各 4 对共 32 对,洗入 8x8 棋盘;若生成结果无解则重试。
 * 对局中无可消除对时,把剩余图案洗回剩余位置(已消除位置保持不变)。
 */
public final class BoardGenerator {

    /** 8 种图案,均为 Windows 自带 Segoe UI Emoji 可正常渲染的水果 */
    public static final List<String> TILE_EMOJIS = List.of(
            "🍎", "🍌", "🍇", "🍓", "🍊", "🍉", "🍒", "🥝");

    private BoardGenerator() {
    }

    /**
     * 生成 size x size 的初始棋盘,保证至少存在一个可消除对。
     */
    public static Cell[] generate(int size, int maxTries) {
        for (int attempt = 0; attempt < maxTries; attempt++) {
            Cell[] board = shuffleNew(size);
            if (hasLegalMove(board, size)) {
                return board;
            }
        }
        // 理论上 8x8 几乎不会走到这里;兜底返回最后一次洗牌结果
        return shuffleNew(size);
    }

    /**
     * 重洗剩余图案:收集未消除格子的图案,洗入未消除位置。
     *
     * @return 是否洗出了至少一个可消除对
     */
    public static boolean reshuffleRemaining(Cell[] board, int size, int maxTries) {
        List<Integer> aliveIds = new ArrayList<>();
        List<String> emojis = new ArrayList<>();
        for (Cell cell : board) {
            if (!cell.isEliminated()) {
                aliveIds.add(cell.getId());
                emojis.add(cell.getEmoji());
            }
        }
        for (int attempt = 0; attempt < maxTries; attempt++) {
            Collections.shuffle(emojis);
            for (int i = 0; i < aliveIds.size(); i++) {
                board[aliveIds.get(i)].setEmoji(emojis.get(i));
            }
            if (hasLegalMove(board, size)) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在至少一个可消除对 */
    public static boolean hasLegalMove(Cell[] board, int size) {
        for (int i = 0; i < board.length; i++) {
            if (board[i].isEliminated()) {
                continue;
            }
            for (int j = i + 1; j < board.length; j++) {
                if (!board[j].isEliminated()
                        && board[i].getEmoji().equals(board[j].getEmoji())
                        && PathValidator.findPath(board, size, i, j) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 有多少个可消除对(用于无解判断,与 hasLegalMove 同源) */
    public static int countLegalMoves(Cell[] board, int size) {
        int count = 0;
        for (int i = 0; i < board.length; i++) {
            if (board[i].isEliminated()) {
                continue;
            }
            for (int j = i + 1; j < board.length; j++) {
                if (!board[j].isEliminated()
                        && board[i].getEmoji().equals(board[j].getEmoji())
                        && PathValidator.findPath(board, size, i, j) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Cell[] shuffleNew(int size) {
        int cells = size * size;
        int pairsPerType = (cells / 2) / TILE_EMOJIS.size();
        List<String> deck = new ArrayList<>();
        for (String emoji : TILE_EMOJIS) {
            for (int i = 0; i < pairsPerType * 2; i++) {
                deck.add(emoji);
            }
        }
        Collections.shuffle(deck);
        Cell[] board = new Cell[cells];
        for (int i = 0; i < cells; i++) {
            board[i] = new Cell(i, deck.get(i), false);
        }
        return board;
    }
}
