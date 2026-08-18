package com.linkduel.game;

import com.linkduel.config.GameProperties;
import com.linkduel.dto.RoomState;
import com.linkduel.service.MatchmakingService;
import com.linkduel.service.RedisKeys;
import com.linkduel.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 定时扫描活跃对局(games:active 登记于 Redis,进程重启后依然有效):
 * <ul>
 *   <li>waiting 超时(双方未按时进入)→ 取消结算;</li>
 *   <li>playing 倒计时到期 → 按分结算;</li>
 *   <li>一方离线超过宽限期 → 判在线方胜;双方离线 → 取消。</li>
 * </ul>
 * 结算本身幂等(Redis 锁 + 唯一键),重复扫描不会重复结算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameSweeper {

    private final StringRedisTemplate redis;
    private final MatchmakingService matchmakingService;
    private final SettlementService settlementService;
    private final GameProperties gameProperties;

    @Scheduled(fixedRate = 2000)
    public void sweep() {
        long now = System.currentTimeMillis();
        Set<String> roomIds = redis.opsForZSet().range(RedisKeys.GAMES_ACTIVE, 0, -1);
        if (roomIds == null || roomIds.isEmpty()) {
            return;
        }
        for (String roomId : roomIds) {
            try {
                RoomState room = matchmakingService.loadRoom(roomId);
                if (room == null) {
                    redis.opsForZSet().remove(RedisKeys.GAMES_ACTIVE, roomId);
                    continue;
                }
                if (room.isWaiting()) {
                    long joinTimeoutAt = room.getCreatedAt()
                            + gameProperties.getJoinTimeoutSeconds() * 1000L;
                    if (now >= joinTimeoutAt) {
                        settlementService.settleGame(roomId, SettlementService.SettleTrigger.JOIN_TIMEOUT);
                    }
                    continue;
                }
                if (room.isPlaying()) {
                    if (now >= room.getDeadline()) {
                        settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);
                        continue;
                    }
                    checkForfeit(roomId, room, now);
                }
            } catch (Exception e) {
                log.error("扫描对局异常 roomId={}", roomId, e);
            }
        }
    }

    private void checkForfeit(String roomId, RoomState room, long now) {
        long graceMillis = gameProperties.getForfeitGraceSeconds() * 1000L;
        boolean aOffline = !room.isOnlineA()
                && room.getOfflineSinceA() > 0
                && now - room.getOfflineSinceA() >= graceMillis;
        boolean bOffline = !room.isOnlineB()
                && room.getOfflineSinceB() > 0
                && now - room.getOfflineSinceB() >= graceMillis;
        if (aOffline && bOffline) {
            settlementService.settleGame(roomId, SettlementService.SettleTrigger.BOTH_OFFLINE);
        } else if (aOffline) {
            settlementService.settleGame(roomId, SettlementService.SettleTrigger.FORFEIT);
        } else if (bOffline) {
            settlementService.settleGame(roomId, SettlementService.SettleTrigger.FORFEIT);
        }
    }
}
