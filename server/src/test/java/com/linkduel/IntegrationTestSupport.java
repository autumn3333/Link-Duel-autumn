package com.linkduel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类。
 * 所有集成测试共用独立 Redis 库(spring.data.redis.database=15),
 * 每个用例前清空,保证可重复运行;MySQL 侧结算测试使用随机邮箱的独立用户,
 * 不干扰种子账号——用例结束后把测试用户与对局记录一并删掉,
 * 保证评审跑完测试再启动应用时排行榜仍是干净的初始状态。
 */
public abstract class IntegrationTestSupport {

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void flushTestDb() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void cleanupTestRows() {
        jdbc.update("DELETE FROM game_records WHERE player_a_id IN (SELECT id FROM users WHERE email LIKE '%@test.com')"
                + " OR player_b_id IN (SELECT id FROM users WHERE email LIKE '%@test.com')");
        jdbc.update("DELETE FROM users WHERE email LIKE '%@test.com'");
    }
}
