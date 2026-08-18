package com.linkduel.dto;

import lombok.Data;

/**
 * 对局状态(存 Redis room:{roomId},JSON 序列化;所有时间均为 epoch millis)。
 * 这是服务端权威状态,客户端只读快照。
 */
@Data
public class RoomState {

    /** r-xxxxxx */
    private String roomId;
    /** waiting | playing | settled */
    private String status;

    private Long playerAId;
    private Long playerBId;
    private String playerANickname;
    private String playerBNickname;

    /** 双方当前在线状态(重连恢复依据) */
    private boolean onlineA;
    private boolean onlineB;
    /** 离线起始时间,0 表示在线 */
    private long offlineSinceA;
    private long offlineSinceB;

    private Cell[] board;

    /** 各自消除对数 */
    private int scoreA;
    private int scoreB;

    private long createdAt;
    /** 进入 playing 的时间 */
    private long startedAt;
    /** playing 结束时间 = startedAt + 对局时长 */
    private long deadline;

    /** 无解时是否已自动洗牌(只允许一次) */
    private boolean reshuffleUsed;

    public boolean isPlayer(Long userId) {
        return userId.equals(playerAId) || userId.equals(playerBId);
    }

    public boolean isWaiting() {
        return "waiting".equals(status);
    }

    public boolean isPlaying() {
        return "playing".equals(status);
    }

    public boolean isSettled() {
        return "settled".equals(status);
    }
}
