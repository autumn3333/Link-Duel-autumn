package com.linkduel.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 在线状态:user:online:{userId},带 TTL。
 * <ul>
 *   <li>值 "1" 表示无会话绑定的在线(REST 匹配入口);</li>
 *   <li>值为会话 id 时,断线事件会做会话比对,防止重连后旧会话迟到的
 *       DISCONNECT 把新连接误判为离线。</li>
 * </ul>
 * STOMP CONNECT 与心跳刷新,断线删除;匹配 Lua 用 EXISTS 过滤,
 * 保证队列里只剩"最近 30 秒活跃"的用户。
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    public static final String KEY_PREFIX = "user:online:";
    private static final Duration TTL = Duration.ofSeconds(30);
    /** 无会话绑定的占位值 */
    private static final String NO_SESSION = "1";

    private final StringRedisTemplate redis;

    /** REST 侧置在线:不覆盖已有的会话绑定值,只刷新 TTL */
    public void setOnline(Long userId) {
        String key = KEY_PREFIX + userId;
        redis.opsForValue().setIfAbsent(key, NO_SESSION, TTL);
        redis.expire(key, TTL);
    }

    /** WS CONNECT / 心跳:绑定当前会话 id */
    public void setOnlineSession(Long userId, String sessionId) {
        redis.opsForValue().set(KEY_PREFIX + userId, sessionId, TTL);
    }

    /**
     * 会话断开。返回 false 表示该断线来自旧会话(当前在线的是新连接),
     * 调用方应忽略,不能据此把玩家判为离线。
     */
    public boolean disconnect(Long userId, String sessionId) {
        String key = KEY_PREFIX + userId;
        String current = redis.opsForValue().get(key);
        // 键已过期(长期无心跳):没有更新的会话可误伤,按真实断线处理
        if (current == null) {
            return true;
        }
        if (NO_SESSION.equals(current) || current.equals(sessionId)) {
            redis.delete(key);
            return true;
        }
        return false;
    }

    public void setOffline(Long userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + userId));
    }
}
