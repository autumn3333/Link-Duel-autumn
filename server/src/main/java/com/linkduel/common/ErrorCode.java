package com.linkduel.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一业务错误码。0 表示成功。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    OK(0, "成功"),
    PARAM_ERROR(40000, "参数错误"),
    WRONG_PASSWORD(40001, "邮箱或密码错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权操作"),
    NOT_IN_GAME(40410, "当前没有进行中的对局"),
    ROOM_NOT_FOUND(40420, "对局不存在或已结束"),
    ALREADY_IN_QUEUE(40900, "已在匹配队列中"),
    ALREADY_MATCHED(40901, "已在匹配中,无法取消"),
    ALREADY_IN_GAME(40903, "已在对局中"),
    GAME_OVER(40902, "对局已结束"),
    INVALID_PATH(42200, "所选两个图案无法连通"),
    CELL_ELIMINATED(42201, "所选格子已被消除"),
    SAME_CELL(42202, "不能选择同一个格子"),
    NOT_YOUR_ROOM(42203, "不属于该对局"),
    INTERNAL_ERROR(50000, "服务器内部错误");

    private final int code;
    private final String defaultMessage;
}
