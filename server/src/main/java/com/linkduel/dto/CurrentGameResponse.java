package com.linkduel.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 重连发现:当前用户是否有一场未结束的对局。
 */
@Data
@AllArgsConstructor
public class CurrentGameResponse {

    /** 无对局时为 null */
    private String roomId;
}
