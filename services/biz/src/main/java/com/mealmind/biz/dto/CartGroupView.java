package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/** 购物车按商家分组（接口 8）。 */
public record CartGroupView(
        String merchantId,
        String merchantName,
        String logoUrl,
        List<CartItemView> items,
        BigDecimal subtotal) {
}
