package com.linkduel.service;

import com.linkduel.IntegrationTestSupport;
import com.linkduel.common.BizException;
import com.linkduel.common.ErrorCode;
import com.linkduel.dto.JoinResult;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.User;
import com.linkduel.game.Match3Engine;
import com.linkduel.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配队列集成测试:入队/重复入队/取消/配对成房(依赖真实 Redis 的 Lua 脚本)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.data.redis.database=15")
class MatchmakingTest extends IntegrationTestSupport {

    @Autowired
    private MatchmakingService matchmakingService;

    @Autowired
    private UserMapper userMapper;

    private User playerA() {
        return userMapper.findByEmail("player_a@example.com");
    }

    private User playerB() {
        return userMapper.findByEmail("player_b@example.com");
    }

    @Test
    void joinTwiceThrowsAlreadyInQueue() {
        assertEquals("queued", matchmakingService.join(playerA()).getStatus());
        BizException ex = assertThrows(BizException.class, () -> matchmakingService.join(playerA()));
        assertEquals(ErrorCode.ALREADY_IN_QUEUE, ex.getErrorCode());
    }

    @Test
    void cancelRemovesFromQueueAndAllowsRejoin() {
        matchmakingService.join(playerA());
        matchmakingService.cancel(playerA());
        assertEquals(0L, redis.opsForZSet().zCard(RedisKeys.MATCH_QUEUE));
        assertEquals("queued", matchmakingService.join(playerA()).getStatus());
    }

    @Test
    void twoPlayersMatchIntoSharedRoom() {
        assertEquals("queued", matchmakingService.join(playerA()).getStatus());
        JoinResult second = matchmakingService.join(playerB());

        assertEquals("matched", second.getStatus());
        String roomId = second.getRoomId();
        assertNotNull(roomId);
        assertEquals("玩家A", second.getOpponent().getNickname());

        // 队列已清空,双方索引指向同一房间,活跃对局已登记
        assertEquals(0L, redis.opsForZSet().zCard(RedisKeys.MATCH_QUEUE));
        assertEquals(roomId, redis.opsForValue().get(RedisKeys.userGame(playerA().getId())));
        assertEquals(roomId, redis.opsForValue().get(RedisKeys.userGame(playerB().getId())));
        assertNotNull(redis.opsForZSet().score(RedisKeys.GAMES_ACTIVE, roomId));

        // 房间状态:初始棋盘 64 格全满、无三连、且至少存在一个可行交换,双方拿到的是同一个房间
        RoomState room = matchmakingService.loadRoom(roomId);
        assertNotNull(room);
        assertEquals("waiting", room.getStatus());
        assertEquals(64, room.getBoard().length);
        assertTrue(Match3Engine.findMatches(room.getBoard(), 8).isEmpty(),
                "初始棋盘不应存在三连");
        assertTrue(Match3Engine.hasValidSwap(room.getBoard(), 8),
                "初始棋盘必须存在可行交换");

        // 已在对局中,不能再匹配
        BizException ex = assertThrows(BizException.class, () -> matchmakingService.join(playerA()));
        assertEquals(ErrorCode.ALREADY_IN_GAME, ex.getErrorCode());
    }
}
