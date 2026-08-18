package com.linkduel.dto;

import lombok.Data;

/**
 * 客户端消除请求:仅两个格子坐标,房间由服务端从 user:game 推导。
 */
@Data
public class MoveRequest {

    private int cellA;
    private int cellB;
}
