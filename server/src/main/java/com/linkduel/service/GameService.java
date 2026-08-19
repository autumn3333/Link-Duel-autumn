package com.linkduel.service;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.config.GameProperties;
import com.linkduel.dto.Cell;
import com.linkduel.dto.RoomState;
import com.linkduel.game.Match3Engine;
import com.linkduel.ws.GameEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 对局服务:权威校验并执行一次"交换三消"操作。
 *
 * <p>客户端只传来两个相邻格子坐标;是否合法完全由服务端根据 Redis 中的
 * 棋盘状态判定(玩家/房间/相邻/能成三连/超时全部校验),得分也只在此累加:
 * 每消除 1 格 +1 记给操作者,连锁消除累加。死局自动洗牌直到有解。
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

    /** 补块/洗牌的随机源(无状态,不注入容器) */
    private final Random random = new SecureRandom();

    /**
     * 执行一次交换尝试。
     *
     * @throws BizException 非法操作(由 STOMP 层转为 /user/queue/errors 通知)
     */
    public void move(Long userId, int from, int to) {
        String roomId = redis.opsForValue().get(RedisKeys.userGame(userId));
        if (roomId == null) {
            throw new BizException(ErrorCode.NOT_IN_GAME);
        }
        // 房间锁串行化同一房间的并发操作(move vs move / move vs settle)
        roomLocks.withLock(roomId, () -> doMove(roomId, userId, from, to));
    }

    private void doMove(String roomId, Long userId, int from, int to) {
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
        if (from < 0 || to < 0 || from >= board.length || to >= board.length) {
            throw new BizException(ErrorCode.PARAM_ERROR, "坐标超出棋盘范围");
        }
        if (from == to) {
            throw new BizException(ErrorCode.SAME_CELL);
        }
        if (!Match3Engine.isAdjacent(size, from, to)) {
            throw new BizException(ErrorCode.NOT_ADJACENT);
        }

        // 引擎就地结算:非法交换返回 null(棋盘已还原);合法则返回各连锁步
        Match3Engine.MoveResult result = Match3Engine.resolveSwap(board, size, from, to, random);
        if (result == null) {
            throw new BizException(ErrorCode.INVALID_SWAP);
        }

        // 计分:每消除 1 格 +1 记给操作者(连锁累加),权威持久化
        int gained = result.getScoreGained();
        if (userId.equals(room.getPlayerAId())) {
            room.setScoreA(room.getScoreA() + gained);
        } else {
            room.setScoreB(room.getScoreB() + gained);
        }
        matchmakingService.saveRoom(room);

        // 实时广播(含操作者):先交换动画,再逐连锁步回放消除+补块后的棋盘
        eventPublisher.toRoom(roomId, "moved", Map.of(
                "byUserId", userId, "from", from, "to", to,
                "scoreA", room.getScoreA(), "scoreB", room.getScoreB()));
        for (Match3Engine.Step step : result.getSteps()) {
            Map<String, Object> data = new HashMap<>();
            data.put("byUserId", userId);
            data.put("cells", step.getCells());
            data.put("board", step.getBoard());
            data.put("scoreA", room.getScoreA());
            data.put("scoreB", room.getScoreB());
            eventPublisher.toRoom(roomId, "cleared", data);
        }

        // 死局(无任何可行交换)→ 自动洗牌直到有解,不限次数
        if (!Match3Engine.hasValidSwap(board, size)) {
            boolean solvable = Match3Engine.reshuffle(
                    board, size, random, gameProperties.getMaxReshuffleTries());
            matchmakingService.saveRoom(room);
            if (solvable) {
                Map<String, Object> data = new HashMap<>();
                data.put("board", board);
                eventPublisher.toRoom(roomId, "reshuffled", data);
            }
        }
    }
}
