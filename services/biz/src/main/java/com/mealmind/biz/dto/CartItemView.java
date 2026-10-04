package com.mealmind.biz.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 购物车条目（接口 8）。 */
public record CartItemView(
        String id,
        String dishId,
        String name,
        String imageUrl,
        String specText,
        BigDecimal unitPrice,
        Integer quantity,
        @JsonProperty("isAvailable") boolean isAvailable,
        @JsonProperty("isCombo") boolean isCombo) {
}
