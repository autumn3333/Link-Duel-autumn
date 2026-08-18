package com.linkduel.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对局最终结算记录(只写入一次,game_id 唯一)。
 */
@Data
public class GameRecord {

    private Long id;
    /** = Redis roomId */
    private String gameId;
    private Long playerAId;
    private Long playerBId;
    private Integer scoreA;
    private Integer scoreB;
    /** NULL = 平局/取消 */
    private Long winnerId;
    /** finished | forfeit | cancelled */
    private String status;
    /** cleared | timeout | no_moves | forfeit | both_disconnected */
    private String reason;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
