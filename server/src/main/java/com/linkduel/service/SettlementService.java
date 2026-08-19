package com.linkduel.service;

import com.linkduel.config.GameProperties;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.GameRecord;
import com.linkduel.entity.User;
import com.linkduel.mapper.GameRecordMapper;
import com.linkduel.mapper.UserMapper;
import com.linkduel.ws.GameEventPublisher;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 幂等结算。所有结束路径(倒计时超时/离线弃赛/进入超时)汇入 settleGame:
 *
 * <pre>
 * 1. Redis SETNX 锁(30s)串行化并发结算;
 * 2. JVM 内房间锁串行化 move vs settle;
 * 3. MySQL 单事务(INSERT game_records 唯一键 + 用户行锁 + 更新统计)= 提交点;
 * 4. 提交后崩溃的重试走"修复路径"(读已落库记录,重算排行榜,不再改分数);
 * 5. 广播 gameover → 清理 Redis 房间相关 key。
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** 结算触发原因 */
    public enum SettleTrigger {
        /** 倒计时结束 */
        TIMEOUT,
        /** 一方离线(立即判在线方胜) */
        FORFEIT,
        /** 匹配后未在限时内进入对局 */
        JOIN_TIMEOUT
    }

    /** 结算结果(纯函数,由房间状态 + 触发原因得出) */
    @Data
    @AllArgsConstructor
    public static class Outcome {
        private Long winnerId;      // null = 平局/取消
        private String status;      // finished | forfeit | cancelled
        private String reason;
        private int deltaA;         // 玩家 A 的积分变化
        private int deltaB;
    }

    private final StringRedisTemplate redis;
    private final RoomLocks roomLocks;
    private final MatchmakingService matchmakingService;
    private final SettlementPersistService persistService;
    private final LeaderboardService leaderboardService;
    private final GameEventPublisher eventPublisher;
    private final UserMapper userMapper;
    private final GameRecordMapper gameRecordMapper;

    public void settleGame(String roomId, SettleTrigger trigger) {
        // 1. Redis 锁:并发/重启后的重复触发在此串行化;锁自身 30s 过期兜底
        Boolean locked = redis.opsForValue()
                .setIfAbsent(RedisKeys.settleLock(roomId), "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            roomLocks.withLock(roomId, () -> doSettle(roomId, trigger));
        } finally {
            redis.delete(RedisKeys.settleLock(roomId));
        }
    }

    private void doSettle(String roomId, SettleTrigger trigger) {
        RoomState room = matchmakingService.loadRoom(roomId);
        if (room == null || room.isSettled()) {
            return;
        }
        Outcome outcome = computeOutcome(room, trigger);
        try {
            // 3. MySQL 事务:INSERT 唯一键是幂等的最终防线
            SettlementPersistService.PersistOutcome persisted = persistService.persist(room, outcome);
            // 4a. 正常路径:排行榜按绝对值更新(天然幂等)
            leaderboardService.updatePoints(room.getPlayerAId(), persisted.newPointsA());
            leaderboardService.updatePoints(room.getPlayerBId(), persisted.newPointsB());
            broadcastGameOver(room, outcome);
        } catch (DuplicateKeyException e) {
            // 4b. 修复路径:本局已经落库(上次结算提交后崩溃),只补排行榜与通知,不再改分数
            log.warn("对局已结算过,走修复路径 roomId={}", roomId);
            repairLeaderboard(roomId);
            broadcastRecordedGameOver(roomId);
        } finally {
            cleanupRoomKeys(roomId, room);
        }
    }

    /** 结算结果计算:只依赖房间状态,不依赖外部输入 */
    private Outcome computeOutcome(RoomState room, SettleTrigger trigger) {
        int scoreA = room.getScoreA();
        int scoreB = room.getScoreB();
        Long winner = null;
        String status = "finished";
        String reason;
        int deltaA = 0;
        int deltaB = 0;
        switch (trigger) {
            case FORFEIT -> {
                winner = room.isOnlineA() ? room.getPlayerAId() : room.getPlayerBId();
                status = "forfeit";
                reason = "forfeit";
                if (winner.equals(room.getPlayerAId())) {
                    deltaA = 3;
                } else {
                    deltaB = 3;
                }
            }
            case JOIN_TIMEOUT -> {
                status = "cancelled";
                reason = "join_timeout";
            }
            case TIMEOUT -> reason = "timeout";
            default -> reason = "unknown";
        }
        // finished 类结算:按对局内得分定胜负
        if ("finished".equals(status)) {
            if (scoreA > scoreB) {
                winner = room.getPlayerAId();
                deltaA = 3;
            } else if (scoreB > scoreA) {
                winner = room.getPlayerBId();
                deltaB = 3;
            } else {
                deltaA = 1; // 平局各 +1
                deltaB = 1;
            }
        }
        return new Outcome(winner, status, reason, deltaA, deltaB);
    }

    private void broadcastGameOver(RoomState room, Outcome outcome) {
        Map<String, Object> data = new HashMap<>();
        data.put("winnerId", outcome.getWinnerId());
        data.put("scoreA", room.getScoreA());
        data.put("scoreB", room.getScoreB());
        data.put("reason", outcome.getReason());
        data.put("status", outcome.getStatus());
        data.put("deltaA", outcome.getDeltaA());
        data.put("deltaB", outcome.getDeltaB());
        eventPublisher.toRoom(room.getRoomId(), "gameover", data);
    }

    private void broadcastRecordedGameOver(String roomId) {
        GameRecord record = gameRecordMapper.findByGameId(roomId);
        if (record == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("winnerId", record.getWinnerId());
        data.put("scoreA", record.getScoreA());
        data.put("scoreB", record.getScoreB());
        data.put("reason", record.getReason());
        data.put("status", record.getStatus());
        data.put("deltaA", null);
        data.put("deltaB", null);
        eventPublisher.toRoom(roomId, "gameover", data);
    }

    /** 修复排行榜:按 MySQL 中的权威积分重写 ZSET */
    private void repairLeaderboard(String roomId) {
        GameRecord record = gameRecordMapper.findByGameId(roomId);
        if (record == null) {
            return;
        }
        User a = userMapper.findById(record.getPlayerAId());
        User b = userMapper.findById(record.getPlayerBId());
        if (a != null) {
            leaderboardService.updatePoints(a.getId(), a.getPoints());
        }
        if (b != null) {
            leaderboardService.updatePoints(b.getId(), b.getPoints());
        }
    }

    /** 清理 Redis:房间、用户索引、活跃登记 */
    private void cleanupRoomKeys(String roomId, RoomState room) {
        redis.delete(RedisKeys.room(roomId));
        redis.delete(RedisKeys.userGame(room.getPlayerAId()));
        redis.delete(RedisKeys.userGame(room.getPlayerBId()));
        redis.opsForZSet().remove(RedisKeys.GAMES_ACTIVE, roomId);
        log.info("对局结算完成并清理 roomId={} trigger-reason={}", roomId,
                room.isSettled() ? "already" : "fresh");
    }
}
