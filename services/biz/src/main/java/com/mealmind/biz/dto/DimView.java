package com.mealmind.biz.dto;

import java.math.BigDecimal;

/** 单维度分（0–10）与置信度（0–1）。 */
public record DimView(String dim, BigDecimal value, BigDecimal confidence) {
}