package com.mealmind.biz.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。响应体中 code 字段在成功时为 0，失败时为枚举名。
 */
public enum ErrorCode {

    UNAUTHORIZED("未登录或登录态已失效", HttpStatus.UNAUTHORIZED),
    INVALID_CATEGORY("品类不存在", HttpStatus.BAD_REQUEST),
    INVALID_SPEC("规格选择不合法", HttpStatus.BAD_REQUEST),
    INVALID_PARAM("请求参数不合法", HttpStatus.BAD_REQUEST),
    QUANTITY_LIMIT("数量超出限制", HttpStatus.BAD_REQUEST),
    DISH_UNAVAILABLE("菜品已下架", HttpStatus.BAD_REQUEST),
    DISH_NOT_FOUND("菜品不存在", HttpStatus.NOT_FOUND),
    MERCHANT_NOT_FOUND("商家不存在", HttpStatus.NOT_FOUND),
    CART_ITEM_NOT_FOUND("购物车条目不存在", HttpStatus.NOT_FOUND),
    SESSION_CREATE_FAILED("会话创建失败", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR("服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus status;

    ErrorCode(String message, HttpStatus status) {
        this.message = message;
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
