package com.mealmind.biz.common;

/**
 * 统一响应体 {code, message, data}。
 * 成功时 code = 0；失败时 code 为 {@link ErrorCode} 名称（字符串）。
 */
public record ApiResponse<T>(Object code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.name(), errorCode.getMessage(), null);
    }
}
