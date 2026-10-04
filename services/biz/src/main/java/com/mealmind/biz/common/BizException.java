package com.mealmind.biz.common;

/**
 * 业务异常：携带 {@link ErrorCode}，由 GlobalExceptionHandler 统一转成响应体与 HTTP 状态码。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
