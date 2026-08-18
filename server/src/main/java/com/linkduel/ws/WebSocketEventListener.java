package com.linkduel.ws;

import com.linkduel.dto.RoomState;
import com.linkduel.service.MatchmakingService;
import com.linkduel.service.PresenceService;
import com.linkduel.service.RedisKeys;
import com.linkduel.service.RoomLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * WS 断线处理:
 * <ul>
 *   <li>排队中掉线 → 移出匹配队列(配合 Lua 的在线过滤,死条目不会卡住队头);</li>
 *   <li>对局中掉线 → 房间在线标志置 false + 记录 offlineSince,广播 player-offline,
 *       GameSweeper 依此执行 90 秒宽限 → 判负 / 双断线取消;</li>
 *   <li>旧会话迟到的 DISCONNECT(重连竞态)由 PresenceService 的会话比对过滤,不误伤新连接。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final PresenceService presenceService;
    private final StringRedisTemplate redis;
    private final MatchmakingService matchmakingService;
    private final RoomLocks roomLocks;
    private final GameEventPublisher eventPublisher;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            return;
        }
        Long userId = (Long) attrs.get("userId");
        if (userId == null) {
            return;
        }
        // 会话比对:重连后旧会话的 DISCONNECT 可能晚到,不能误把新连接判为离线
        if (!presenceService.disconnect(userId, event.getSessionId())) {
            return;
        }
        // 排队中掉线:移出队列
        redis.opsForZSet().remove(RedisKeys.MATCH_QUEUE, String.valueOf(userId));
        // 对局中掉线:标记离线,进入宽限期(在线方继续玩,对局不暂停)
        String roomId = redis.opsForValue().get(RedisKeys.userGame(userId));
        if (roomId == null) {
            return;
        }
        roomLocks.withLock(roomId, () -> markOfflineInRoom(roomId, userId));
    }

    private void markOfflineInRoom(String roomId, Long userId) {
        RoomState room = matchmakingService.loadRoom(roomId);
        if (room == null || room.isSettled()) {
            return;
        }
        boolean changed = false;
        long now = System.currentTimeMillis();
        if (userId.equals(room.getPlayerAId()) && room.isOnlineA()) {
            room.setOnlineA(false);
            room.setOfflineSinceA(now);
            changed = true;
        } else if (userId.equals(room.getPlayerBId()) && room.isOnlineB()) {
            room.setOnlineB(false);
            room.setOfflineSinceB(now);
            changed = true;
        }
        if (changed) {
            matchmakingService.saveRoom(room);
            eventPublisher.toRoom(roomId, "player-offline", Map.of("userId", userId));
            log.info("玩家断线 roomId={} userId={}", roomId, userId);
        }
    }
}
