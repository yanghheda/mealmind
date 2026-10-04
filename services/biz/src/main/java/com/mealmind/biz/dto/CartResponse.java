package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/** 接口 8：购物车响应。 */
public record CartResponse(
        List<CartGroupView> groups,
        Integer totalQuantity,
        BigDecimal totalAmount) {
}
