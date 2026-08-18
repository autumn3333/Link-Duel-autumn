package com.linkduel.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 每房间一把 JVM 内锁:串行化同一房间的 move 与结算,避免并发修改棋盘状态。
 * 单实例部署下足够;多实例需换成 Redis 分布式锁(DESIGN.md 扩展方向)。
 */
@Component
public class RoomLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String roomId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(roomId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(String roomId, Runnable action) {
        withLock(roomId, () -> {
            action.run();
            return null;
        });
    }
}
