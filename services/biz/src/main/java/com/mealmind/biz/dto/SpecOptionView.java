package com.mealmind.biz.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 规格选项。 */
public record SpecOptionView(
        String optionId,
        String optionName,
        BigDecimal extraPrice,
        @JsonProperty("isDefault") boolean isDefault) {
}
