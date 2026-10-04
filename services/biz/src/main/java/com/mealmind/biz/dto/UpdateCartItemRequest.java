package com.mealmind.biz.dto;

/** 接口 10：修改数量请求（quantity = 0 表示删除）。 */
public record UpdateCartItemRequest(String itemId, Integer quantity) {
}
