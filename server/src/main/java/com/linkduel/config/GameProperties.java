package com.linkduel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对局相关配置(application.yml 的 game.* 段)
 */
@Data
@Component
@ConfigurationProperties(prefix = "game")
public class GameProperties {

    /** 对局时长(秒),限时积分制 */
    private int durationSeconds = 120;

    /** 对局状态在 Redis 的 TTL(分钟) */
    private int roomTtlMinutes = 30;

    /** 匹配成功后等待双方进入对局的超时(秒) */
    private int joinTimeoutSeconds = 60;

    /** 洗牌重试上限 */
    private int maxReshuffleTries = 10;

    /** 棋盘边长(8x8) */
    private int boardSize = 8;
}
