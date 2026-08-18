-- Link-Duel 初始表结构:用户 + 对局记录
-- game_records.uk_game_id 是结算幂等的最终防线

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    nickname      VARCHAR(64)  NOT NULL,
    points        INT          NOT NULL DEFAULT 0,
    wins          INT          NOT NULL DEFAULT 0,
    losses        INT          NOT NULL DEFAULT 0,
    draws         INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE game_records (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id     VARCHAR(40) NOT NULL COMMENT '= Redis roomId',
    player_a_id BIGINT      NOT NULL,
    player_b_id BIGINT      NOT NULL,
    score_a     INT         NOT NULL DEFAULT 0 COMMENT 'A 消除对数',
    score_b     INT         NOT NULL DEFAULT 0 COMMENT 'B 消除对数',
    winner_id   BIGINT      NULL COMMENT 'NULL = 平局/取消',
    status      VARCHAR(16) NOT NULL COMMENT 'finished | forfeit | cancelled',
    reason      VARCHAR(32) NOT NULL COMMENT 'cleared | timeout | no_moves | forfeit | both_disconnected',
    started_at  DATETIME    NOT NULL,
    ended_at    DATETIME    NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_game_id (game_id),
    KEY idx_player_a (player_a_id, ended_at),
    KEY idx_player_b (player_b_id, ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
