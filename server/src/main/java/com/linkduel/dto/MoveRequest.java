package com.linkduel.dto;

import lombok.Data;

/**
 * 客户端交换请求:两个正交相邻格子坐标,房间由服务端从 user:game 推导。
 */
@Data
public class MoveRequest {

    private int from;
    private int to;
}
