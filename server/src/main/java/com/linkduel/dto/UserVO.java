package com.linkduel.dto;

import com.linkduel.entity.User;
import lombok.Data;

/**
 * 对外暴露的用户信息(绝不包含 passwordHash)。
 */
@Data
public class UserVO {

    private Long id;
    private String email;
    private String nickname;
    private Integer points;
    private Integer wins;
    private Integer losses;
    private Integer draws;

    public static UserVO from(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setPoints(user.getPoints());
        vo.setWins(user.getWins());
        vo.setLosses(user.getLosses());
        vo.setDraws(user.getDraws());
        return vo;
    }
}
