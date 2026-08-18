package com.linkduel.dto;

import lombok.Data;

@Data
public class LeaderboardVO {

    private Integer rank;
    private Long userId;
    private String nickname;
    private Integer points;
    private Boolean isMe;
}
