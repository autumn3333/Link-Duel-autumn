package com.linkduel.ws;

import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.config.GameProperties;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.User;
import com.linkduel.mapper.UserMapper;
import com.linkduel.security.JwtUtil;
import com.linkduel.service.MatchmakingService;
import com.linkduel.service.PresenceService;
import com.linkduel.service.RedisKeys;
import com.linkduel.service.RoomLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * STOMP 入站拦截器:
 * <ul>
 *   <li>CONNECT:校验 CONNECT 头里的 JWT,setUser 使 /user/queue 路由可用,标记在线;</li>
 *   <li>SUBSCRIBE /topic/game/{roomId}:校验房间成员身份,更新在线状态,
 *       waiting 双方到齐时转入 playing,并延迟 ~200ms 推送全量快照
 *      (立即推送可能早于订阅注册完成,消息会丢失)。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final MatchmakingService matchmakingService;
    private final StringRedisTemplate redis;
    private final RoomLocks roomLocks;
    private final GameProperties gameProperties;
    private final GameEventPublisher eventPublisher;
    private final TaskScheduler taskScheduler;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return handleSubscribe(accessor) ? message : null;
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String auth = accessor.getFirstNativeHeader("Authorization");
        Long userId = (auth != null && auth.startsWith("Bearer "))
                ? jwtUtil.parseUserId(auth.substring(7))
                : null;
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        accessor.setUser(new StompPrincipal(String.valueOf(userId)));
        accessor.getSessionAttributes().put("userId", userId);
        // 绑定会话 id:断线事件据此区分旧会话与新连接(重连竞态防护)
        presenceService.setOnlineSession(userId, accessor.getSessionId());
        // 已在对局中的用户重连:立即更新房间在线标志(宽限期计时从这里停止)
        String roomId = redis.opsForValue().get(RedisKeys.userGame(userId));
        if (roomId != null) {
            markPlayerOnline(roomId, userId);
        }
    }

    /**
     * 返回 false 表示丢弃该 SUBSCRIBE 帧。
     * 注意:校验失败绝不能抛异常——inbound 通道的异常会一路穿透到
     * StompSubProtocolHandler,导致整个 WebSocket 连接被关闭(而非只拒绝本次订阅),
     * 客户端只是订阅了一个不存在/不属于自己的房间就会被迫断线重连。
     * 正确做法:丢弃该帧 + 向 /user/queue/errors 回错误码,连接保持。
     */
    private boolean handleSubscribe(StompHeaderAccessor accessor) {
        Long userId = (Long) accessor.getSessionAttributes().get("userId");
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/game/")) {
            return true;
        }
        String roomId = destination.substring("/topic/game/".length());
        RoomState room = matchmakingService.loadRoom(roomId);
        if (room == null || room.isSettled()) {
            sendError(userId, ErrorCode.GAME_OVER);
            return false;
        }
        if (!room.isPlayer(userId)) {
            sendError(userId, ErrorCode.NOT_YOUR_ROOM);
            return false;
        }
        markPlayerOnline(roomId, userId);
        scheduleSnapshot(userId, roomId);
        return true;
    }

    /** 拒绝订阅时点对点回错误码(信封格式与 GameEventController 一致) */
    private void sendError(Long userId, ErrorCode errorCode) {
        eventPublisher.toUser(userId, "errors", "error",
                Map.of("code", errorCode.getCode(), "message", errorCode.getDefaultMessage()));
    }

    /** 更新房间内在线标志 + 双方到齐时 waiting → playing(幂等) */
    private void markPlayerOnline(String roomId, Long userId) {
        roomLocks.withLock(roomId, () -> {
            RoomState room = matchmakingService.loadRoom(roomId);
            if (room == null || room.isSettled()) {
                return;
            }
            boolean changed = false;
            if (userId.equals(room.getPlayerAId()) && !room.isOnlineA()) {
                room.setOnlineA(true);
                room.setOfflineSinceA(0);
                changed = true;
            } else if (userId.equals(room.getPlayerBId()) && !room.isOnlineB()) {
                room.setOnlineB(true);
                room.setOfflineSinceB(0);
                changed = true;
            }
            if (room.isWaiting() && room.isOnlineA() && room.isOnlineB()) {
                startPlaying(room);
                changed = true;
            }
            if (changed) {
                matchmakingService.saveRoom(room);
                eventPublisher.toRoom(roomId, "player-online", Map.of("userId", userId));
            }
        });
    }

    /** waiting → playing:记录开始时间与截止时间,更新 games:active 的下次动作时间 */
    private void startPlaying(RoomState room) {
        long now = System.currentTimeMillis();
        room.setStatus("playing");
        room.setStartedAt(now);
        room.setDeadline(now + gameProperties.getDurationSeconds() * 1000L);
        redis.opsForZSet().add(RedisKeys.GAMES_ACTIVE, room.getRoomId(), room.getDeadline());
        // 后进入的玩家可能只拿到 waiting 快照,广播 started 让其客户端进入对局
        eventPublisher.toRoom(room.getRoomId(), "started",
                Map.of("startedAt", room.getStartedAt(), "deadline", room.getDeadline()));
    }

    /** 延迟 200ms 推送快照,避开"消息先于订阅注册到达"的竞态 */
    private void scheduleSnapshot(Long userId, String roomId) {
        taskScheduler.schedule(
                () -> pushSnapshot(userId, roomId),
                Instant.now().plusMillis(200));
    }

    private void pushSnapshot(Long userId, String roomId) {
        RoomState room = matchmakingService.loadRoom(roomId);
        if (room == null || room.isSettled()) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", room.getRoomId());
        data.put("status", room.getStatus());
        data.put("board", room.getBoard());
        data.put("scoreA", room.getScoreA());
        data.put("scoreB", room.getScoreB());
        data.put("startedAt", room.getStartedAt());
        data.put("deadline", room.getDeadline());
        data.put("reshuffleUsed", room.isReshuffleUsed());
        data.put("players", Map.of(
                "a", Map.of("id", room.getPlayerAId(), "nickname", room.getPlayerANickname(), "online", room.isOnlineA()),
                "b", Map.of("id", room.getPlayerBId(), "nickname", room.getPlayerBNickname(), "online", room.isOnlineB())));
        eventPublisher.toUser(userId, "snapshot", "snapshot", data);
    }
}
