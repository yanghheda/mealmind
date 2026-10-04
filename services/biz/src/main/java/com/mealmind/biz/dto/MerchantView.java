package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 商家视图，接口 4/5/6 共用（对齐技术方案 §4.2 接口 5 的 Merchant 契约）。
 */
public record MerchantView(
        String id,
        String name,
        String logoUrl,
        BigDecimal rating,
        Integer monthlySales,
        Integer deliveryMinutes,
        Integer distanceMeters,
        BigDecimal minOrderAmount,
        BigDecimal deliveryFee,
        @JsonProperty("isOpen") boolean isOpen,
        String rankLabel,
        List<String> promoTags,
        List<String> featureTags,
        List<PreviewDish> previewDishes) {
}
