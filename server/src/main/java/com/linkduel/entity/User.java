package com.linkduel.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户。注意:MyBatis 结果映射需要无参构造 + setter,只使用 @Data,不要用 @Builder。
 */
@Data
public class User {

    private Long id;
    private String email;
    private String passwordHash;
    private String nickname;
    private Integer points;
    private Integer wins;
    private Integer losses;
    private Integer draws;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
