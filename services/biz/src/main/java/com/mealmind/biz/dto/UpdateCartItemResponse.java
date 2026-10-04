package com.mealmind.biz.dto;

/** 接口 10：修改数量响应。 */
public record UpdateCartItemResponse(String itemId, Integer quantity, boolean deleted) {
}
