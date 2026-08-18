package com.linkduel.service;

/**
 * 全部 Redis key 统一定义(详见 DESIGN.md 的 key 设计表)。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 匹配队列 ZSET:member=userId,score=入队时间 */
    public static final String MATCH_QUEUE = "match:queue";

    /** 在线状态前缀(完整 key = user:online:{userId}) */
    public static final String USER_ONLINE_PREFIX = "user:online:";

    /** 对局状态(JSON 字符串,TTL 覆盖重连窗口) */
    public static final String ROOM_PREFIX = "room:";

    /** 用户 → 对局索引(重连发现用) */
    public static final String USER_GAME_PREFIX = "user:game:";

    /** 结算锁:SET NX EX 30 */
    public static final String SETTLE_LOCK_PREFIX = "lock:settle:";

    /** 活跃对局 ZSET:member=roomId,score=下次动作时间 */
    public static final String GAMES_ACTIVE = "games:active";

    /** 排行榜 ZSET:member=userId,score=积分 */
    public static final String LEADERBOARD = "leaderboard:points";

    public static String room(Long roomId) {
        return ROOM_PREFIX + roomId;
    }

    public static String room(String roomId) {
        return ROOM_PREFIX + roomId;
    }

    public static String userGame(Long userId) {
        return USER_GAME_PREFIX + userId;
    }

    public static String settleLock(String roomId) {
        return SETTLE_LOCK_PREFIX + roomId;
    }
}
