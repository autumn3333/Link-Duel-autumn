package com.linkduel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.config.GameProperties;
import com.linkduel.dto.Cell;
import com.linkduel.dto.JoinResult;
import com.linkduel.dto.RoomState;
import com.linkduel.dto.UserVO;
import com.linkduel.entity.User;
import com.linkduel.game.BoardGenerator;
import com.linkduel.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

/**
 * 匹配:Redis ZSET 队列 + Lua 原子配对。
 * 配对成功即创建房间(RoomState 存 Redis)并建立 user:game 双向索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingService {

    private static final String HEX = "0123456789abcdef";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserMapper userMapper;
    private final GameProperties gameProperties;
    private final PresenceService presenceService;
    private final DefaultRedisScript<List> matchScript;

    /**
     * 加入匹配。返回 queued(继续等)或 matched(当场配对成功)。
     */
    public JoinResult join(User user) {
        Long userId = user.getId();

        // 已在某对局中
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.userGame(userId)))) {
            throw new BizException(ErrorCode.ALREADY_IN_GAME);
        }
        // 已在队列中
        if (redis.opsForZSet().score(RedisKeys.MATCH_QUEUE, String.valueOf(userId)) != null) {
            throw new BizException(ErrorCode.ALREADY_IN_QUEUE);
        }

        // 先标记在线,保证 Lua 的在线过滤能看到自己
        presenceService.setOnline(userId);

        long now = System.currentTimeMillis();
        List<Object> pair = redis.execute(
                matchScript,
                List.of(RedisKeys.MATCH_QUEUE, RedisKeys.USER_ONLINE_PREFIX),
                String.valueOf(userId), String.valueOf(now));

        if (pair == null || pair.size() < 2) {
            return new JoinResult("queued", null, null);
        }

        Long first = Long.valueOf(String.valueOf(pair.get(0)));
        Long second = Long.valueOf(String.valueOf(pair.get(1)));
        // 队列按入队时间排序,pair[0] 是先入队者;自己可能是其中任何一个
        Long otherId = first.equals(userId) ? second : first;
        User opponent = userMapper.findById(otherId);
        if (opponent == null) {
            // 极端情况:配对后账号消失,把对方放回队列
            redis.opsForZSet().add(RedisKeys.MATCH_QUEUE, String.valueOf(otherId), now);
            return new JoinResult("queued", null, null);
        }

        String roomId = createRoom(otherId, userId, opponent, user);
        log.info("匹配成功 roomId={} playerA={} playerB={}", roomId, otherId, userId);
        return new JoinResult("matched", roomId, UserVO.from(opponent));
    }

    /** 取消排队(已配对进入房间则报错) */
    public void cancel(User user) {
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.userGame(user.getId())))) {
            throw new BizException(ErrorCode.ALREADY_MATCHED);
        }
        redis.opsForZSet().remove(RedisKeys.MATCH_QUEUE, String.valueOf(user.getId()));
    }

    /**
     * 创建房间:生成可玩棋盘、写 RoomState、建 user:game 索引、登记 games:active。
     * playerA 为队列中先入队者。
     */
    private String createRoom(Long playerAId, Long playerBId, User userA, User userB) {
        String roomId = "r-" + randomHex(6);
        long now = System.currentTimeMillis();
        int size = gameProperties.getBoardSize();
        Cell[] board = BoardGenerator.generate(size, gameProperties.getMaxReshuffleTries());

        RoomState room = new RoomState();
        room.setRoomId(roomId);
        room.setStatus("waiting");
        room.setPlayerAId(playerAId);
        room.setPlayerBId(playerBId);
        room.setPlayerANickname(userA.getNickname());
        room.setPlayerBNickname(userB.getNickname());
        room.setOnlineA(true);
        room.setOnlineB(true);
        room.setBoard(board);
        room.setScoreA(0);
        room.setScoreB(0);
        room.setCreatedAt(now);
        room.setReshuffleUsed(false);
        saveRoom(room);

        Duration ttl = Duration.ofMinutes(gameProperties.getRoomTtlMinutes());
        redis.opsForValue().set(RedisKeys.userGame(playerAId), roomId, ttl);
        redis.opsForValue().set(RedisKeys.userGame(playerBId), roomId, ttl);

        // waiting 阶段的"下次动作时间" = 创建时间 + 进入超时(秒)
        long joinTimeoutAt = now + gameProperties.getJoinTimeoutSeconds() * 1000L;
        redis.opsForZSet().add(RedisKeys.GAMES_ACTIVE, roomId, joinTimeoutAt);
        return roomId;
    }

    public void saveRoom(RoomState room) {
        try {
            String json = objectMapper.writeValueAsString(room);
            redis.opsForValue().set(RedisKeys.room(room.getRoomId()), json,
                    Duration.ofMinutes(gameProperties.getRoomTtlMinutes()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "对局状态序列化失败");
        }
    }

    public RoomState loadRoom(String roomId) {
        String json = redis.opsForValue().get(RedisKeys.room(roomId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RoomState.class);
        } catch (Exception e) {
            log.error("对局状态反序列化失败 roomId={}", roomId, e);
            return null;
        }
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX.charAt(RANDOM.nextInt(HEX.length())));
        }
        return sb.toString();
    }
}
