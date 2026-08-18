package com.linkduel;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 集成测试基类。
 * 所有集成测试共用独立 Redis 库(spring.data.redis.database=15),
 * 每个用例前清空,保证可重复运行;MySQL 侧结算测试使用随机邮箱的独立用户,
 * 不干扰种子账号。
 */
public abstract class IntegrationTestSupport {

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void flushTestDb() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }
}
