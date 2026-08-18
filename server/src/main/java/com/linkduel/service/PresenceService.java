package com.linkduel.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 在线状态:user:online:{userId},带 TTL。
 * STOMP CONNECT 与心跳刷新,断线删除;REST 匹配入口也会先置在线,
 * 保证匹配 Lua 的 EXISTS 过滤看到的都是"最近 30 秒活跃"的用户。
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    public static final String KEY_PREFIX = "user:online:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public void setOnline(Long userId) {
        redis.opsForValue().set(KEY_PREFIX + userId, "1", TTL);
    }

    public void setOffline(Long userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + userId));
    }
}
