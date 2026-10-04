package com.mealmind.biz.dto;

import java.util.List;

/** 接口 9：加入购物车请求。 */
public record AddCartItemRequest(String dishId, List<String> specOptionIds, Integer quantity) {
}
