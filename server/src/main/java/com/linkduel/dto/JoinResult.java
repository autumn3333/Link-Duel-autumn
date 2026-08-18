package com.linkduel.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 匹配入口的同步返回:queued(继续等待)或 matched(立即配对成功)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinResult {

    private String status;
    private String roomId;
    private UserVO opponent;
}
