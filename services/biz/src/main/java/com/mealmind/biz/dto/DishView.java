package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 菜单菜品（接口 7）。 */
public record DishView(
        String id,
        String name,
        String description,
        String imageUrl,
        BigDecimal price,
        BigDecimal originPrice,
        Integer monthlySales,
        @JsonProperty("isAvailable") boolean isAvailable,
        @JsonProperty("isCombo") boolean isCombo,
        @JsonProperty("priceFrom") boolean priceFrom,
        List<DishTagView> tags,
        List<SpecGroupView> specGroups) {
}
