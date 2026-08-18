package com.linkduel.service;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.config.GameProperties;
import com.linkduel.dto.Cell;
import com.linkduel.dto.RoomState;
import com.linkduel.game.BoardGenerator;
import com.linkduel.game.PathValidator;
import com.linkduel.ws.GameEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对局服务:权威校验并执行消除操作。
 *
 * <p>客户端传来的只有两个格子坐标;是否合法完全由服务端根据 Redis 中的
 * 棋盘状态判定(玩家/房间/坐标/重复操作/超时全部校验),得分也只在此累加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final StringRedisTemplate redis;
    private final RoomLocks roomLocks;
    private final MatchmakingService matchmakingService;
    private final SettlementService settlementService;
    private final GameEventPublisher eventPublisher;
    private final GameProperties gameProperties;

    /**
     * 执行一次消除尝试。
     *
     * @throws BizException 非法操作(由 STOMP 层转为 /user/queue/errors 通知)
     */
    public void move(Long userId, int cellA, int cellB) {
        String roomId = redis.opsForValue().get(RedisKeys.userGame(userId));
        if (roomId == null) {
            throw new BizException(ErrorCode.NOT_IN_GAME);
        }
        // 房间锁串行化同一房间的并发操作(move vs move / move vs settle)
        roomLocks.withLock(roomId, () -> doMove(roomId, userId, cellA, cellB));
    }

    private void doMove(String roomId, Long userId, int cellA, int cellB) {
        RoomState room = matchmakingService.loadRoom(roomId);
        if (room == null || room.isSettled()) {
            throw new BizException(ErrorCode.GAME_OVER);
        }
        if (room.isWaiting()) {
            throw new BizException(ErrorCode.GAME_NOT_STARTED);
        }
        if (!room.isPlayer(userId)) {
            throw new BizException(ErrorCode.NOT_YOUR_ROOM);
        }

        long now = System.currentTimeMillis();
        // 超时后的迟到操作:先触发结算,再告知对局已结束
        if (now >= room.getDeadline()) {
            settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);
            throw new BizException(ErrorCode.GAME_OVER);
        }

        Cell[] board = room.getBoard();
        int size = gameProperties.getBoardSize();
        if (cellA < 0 || cellB < 0 || cellA >= board.length || cellB >= board.length) {
            throw new BizException(ErrorCode.PARAM_ERROR, "坐标超出棋盘范围");
        }
        if (cellA == cellB) {
            throw new BizException(ErrorCode.SAME_CELL);
        }
        if (board[cellA].isEliminated() || board[cellB].isEliminated()) {
            throw new BizException(ErrorCode.CELL_ELIMINATED);
        }
        List<Integer> path = PathValidator.findPath(board, size, cellA, cellB);
        if (path == null) {
            throw new BizException(ErrorCode.INVALID_PATH);
        }

        // 权威状态变更:消除两格、得分 +1、持久化
        String emoji = board[cellA].getEmoji();
        board[cellA].setEliminated(true);
        board[cellB].setEliminated(true);
        if (userId.equals(room.getPlayerAId())) {
            room.setScoreA(room.getScoreA() + 1);
        } else {
            room.setScoreB(room.getScoreB() + 1);
        }
        matchmakingService.saveRoom(room);

        // 实时广播:双方客户端都据此播放动画、更新比分(含消除者自己)
        Map<String, Object> data = new HashMap<>();
        data.put("byUserId", userId);
        data.put("cellA", cellA);
        data.put("cellB", cellB);
        data.put("emoji", emoji);
        data.put("path", path);
        data.put("scoreA", room.getScoreA());
        data.put("scoreB", room.getScoreB());
        eventPublisher.toRoom(roomId, "eliminated", data);

        // 终局检查
        if (isAllEliminated(board)) {
            settlementService.settleGame(roomId, SettlementService.SettleTrigger.CLEARED);
            return;
        }
        if (!BoardGenerator.hasLegalMove(board, size)) {
            handleNoMoves(roomId, room, board, size);
        }
    }

    /** 棋盘剩余图案但无可消除对:允许自动洗牌一次,之后仍无解则按分结算 */
    private void handleNoMoves(String roomId, RoomState room, Cell[] board, int size) {
        if (!room.isReshuffleUsed()) {
            room.setReshuffleUsed(true);
            boolean solvable = BoardGenerator.reshuffleRemaining(
                    board, size, gameProperties.getMaxReshuffleTries());
            matchmakingService.saveRoom(room);
            if (solvable) {
                Map<String, Object> data = new HashMap<>();
                data.put("board", board);
                data.put("reason", "no_moves");
                eventPublisher.toRoom(roomId, "reshuffled", data);
                return;
            }
        }
        settlementService.settleGame(roomId, SettlementService.SettleTrigger.NO_MOVES);
    }

    private boolean isAllEliminated(Cell[] board) {
        for (Cell cell : board) {
            if (!cell.isEliminated()) {
                return false;
            }
        }
        return true;
    }
}
