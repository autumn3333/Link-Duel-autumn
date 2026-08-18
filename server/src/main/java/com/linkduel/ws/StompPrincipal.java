package com.linkduel.ws;

import java.security.Principal;

/**
 * STOMP 会话主体,name 即 userId 字符串。
 * 设置后 /user/queue/** 点对点路由与 @MessageMapping 方法的 Principal 参数都可用。
 */
public record StompPrincipal(String name) implements Principal {

    /** Principal 接口要求 getName();record 自动生成的访问器是 name(),需显式补上 */
    @Override
    public String getName() {
        return name;
    }
}
