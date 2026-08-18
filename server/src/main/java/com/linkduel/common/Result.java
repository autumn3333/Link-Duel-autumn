package com.linkduel.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体:{code, msg, data}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.getCode(), "success", data);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getDefaultMessage(), null);
    }

    public static <T> Result<T> error(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }
}
