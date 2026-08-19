package com.linkduel.service;

import com.linkduel.IntegrationTestSupport;
import com.linkduel.dto.JoinResult;
import com.linkduel.dto.RoomState;
import com.linkduel.entity.GameRecord;
import com.linkduel.entity.User;
import com.linkduel.game.GameSweeper;
import com.linkduel.mapper.GameRecordMapper;
import com.linkduel.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 结算幂等 + 定时扫描集成测试。
 * 每个用例创建独立测试用户(随机邮箱),可重复运行,不污染种子账号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.data.redis.database=15")
class SettlementIdempotencyTest extends IntegrationTestSupport {

    @Autowired
    private MatchmakingService matchmakingService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private GameSweeper gameSweeper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private GameRecordMapper gameRecordMapper;

    private User createUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("test-only-hash");
        u.setNickname(email.substring(0, email.indexOf('@')));
        u.setPoints(0);
        u.setWins(0);
        u.setLosses(0);
        u.setDraws(0);
        userMapper.insert(u);
        return u;
    }

    /** 两个新用户配对成房,返回 roomId */
    private String matchTwoUsers(User a, User b) {
        matchmakingService.join(a);
        JoinResult r = matchmakingService.join(b);
        assertNotNull(r.getRoomId());
        return r.getRoomId();
    }

    @Test
    void settlePersistsExactlyOnceEvenIfTriggeredTwice() {
        User c = createUser("settle_c_" + System.nanoTime() + "@test.com");
        User d = createUser("settle_d_" + System.nanoTime() + "@test.com");
        String roomId = matchTwoUsers(c, d);

        RoomState room = matchmakingService.loadRoom(roomId);
        room.setScoreA(2);
        room.setScoreB(1);
        matchmakingService.saveRoom(room);

        settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);
        // 重复触发(模拟重复请求):第二次应是无副作用空操作
        settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);

        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("finished", rec.getStatus());
        assertEquals("timeout", rec.getReason());
        assertEquals(2, rec.getScoreA());
        assertEquals(1, rec.getScoreB());
        assertEquals(c.getId(), rec.getWinnerId());

        // 积分按绝对值更新:胜 +3 / 负 0,重复结算没有叠加
        assertEquals(3, userMapper.findById(c.getId()).getPoints());
        assertEquals(0, userMapper.findById(d.getId()).getPoints());

        // 排行榜与 MySQL 一致(绝对值 ZADD,幂等)
        assertEquals(3.0, redis.opsForZSet().score(RedisKeys.LEADERBOARD, String.valueOf(c.getId())));
        assertEquals(0.0, redis.opsForZSet().score(RedisKeys.LEADERBOARD, String.valueOf(d.getId())));

        // 房间相关 key 全部清理
        assertNull(matchmakingService.loadRoom(roomId));
        assertNull(redis.opsForValue().get(RedisKeys.userGame(c.getId())));
        assertNull(redis.opsForValue().get(RedisKeys.userGame(d.getId())));
        assertNull(redis.opsForZSet().score(RedisKeys.GAMES_ACTIVE, roomId));
    }

    @Test
    void resettleAfterCommitCrashRepairsWithoutDoubleScoring() {
        User c = createUser("repair_c_" + System.nanoTime() + "@test.com");
        User d = createUser("repair_d_" + System.nanoTime() + "@test.com");
        String roomId = matchTwoUsers(c, d);

        RoomState room = matchmakingService.loadRoom(roomId);
        room.setScoreA(5);
        room.setScoreB(0);
        matchmakingService.saveRoom(room);
        // 结算前的原始状态留档,用于模拟崩溃残留
        String zombieJson = redis.opsForValue().get(RedisKeys.room(roomId));

        settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);

        // 模拟"MySQL 已提交、Redis 清理前崩溃":旧房间状态残留,重试结算
        redis.opsForValue().set(RedisKeys.room(roomId), zombieJson);
        settlementService.settleGame(roomId, SettlementService.SettleTrigger.TIMEOUT);

        // 落库仍是首次结算的结果,没有第二次计分
        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals(5, rec.getScoreA());
        assertEquals(3, userMapper.findById(c.getId()).getPoints());

        // 修复路径按 MySQL 权威积分同步了排行榜
        assertEquals(3.0, redis.opsForZSet().score(RedisKeys.LEADERBOARD, String.valueOf(c.getId())));

        // 修复路径同样完成了 Redis 清理
        assertNull(matchmakingService.loadRoom(roomId));
    }

    @Test
    void sweeperSettlesExpiredPlayingRoom() {
        User e = createUser("sweep_e_" + System.nanoTime() + "@test.com");
        User f = createUser("sweep_f_" + System.nanoTime() + "@test.com");
        String roomId = matchTwoUsers(e, f);

        long now = System.currentTimeMillis();
        RoomState room = matchmakingService.loadRoom(roomId);
        room.setStatus("playing");
        room.setStartedAt(now - 10_000);
        room.setDeadline(now - 1_000); // 已过期
        matchmakingService.saveRoom(room);
        redis.opsForZSet().add(RedisKeys.GAMES_ACTIVE, roomId, now - 1_000);

        gameSweeper.sweep();

        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("finished", rec.getStatus());
        assertEquals("timeout", rec.getReason());
        // 0:0 平局,各 +1
        assertEquals(1, userMapper.findById(e.getId()).getPoints());
        assertEquals(1, userMapper.findById(f.getId()).getPoints());
        assertNull(matchmakingService.loadRoom(roomId));
    }

    @Test
    void forfeitSettlesImmediatelyWhenPlayerOffline() {
        User g = createUser("forfeit_g_" + System.nanoTime() + "@test.com");
        User h = createUser("forfeit_h_" + System.nanoTime() + "@test.com");
        String roomId = matchTwoUsers(g, h);

        long now = System.currentTimeMillis();
        RoomState room = matchmakingService.loadRoom(roomId);
        room.setStatus("playing");
        room.setStartedAt(now - 10_000);
        room.setDeadline(now + 60_000);
        // B 掉线:WebSocketEventListener 先标记离线,再立即触发 FORFEIT 结算(不再有宽限)
        room.setOnlineB(false);
        matchmakingService.saveRoom(room);

        settlementService.settleGame(roomId, SettlementService.SettleTrigger.FORFEIT);

        GameRecord rec = gameRecordMapper.findByGameId(roomId);
        assertNotNull(rec);
        assertEquals("forfeit", rec.getStatus());
        assertEquals(g.getId(), rec.getWinnerId()); // 在线方胜
        assertEquals(3, userMapper.findById(g.getId()).getPoints());
        assertEquals(0, userMapper.findById(h.getId()).getPoints());
        assertNull(matchmakingService.loadRoom(roomId));
    }
}
