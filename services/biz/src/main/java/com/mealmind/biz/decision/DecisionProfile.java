package com.mealmind.biz.decision;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** PRD 5.2 偏好结构在业务侧的解析视图（字段与 Agent 输出的 snake_case 对齐）。 */
public record DecisionProfile(
        @JsonProperty("taste_tags") List<String> tasteTags,
        @JsonProperty("budget_max") BigDecimal budgetMax,
        @JsonProperty("avoid_ingredients") List<String> avoidIngredients,
        @JsonProperty("max_delivery_min") Integer maxDeliveryMin,
        String scene,
        @JsonProperty("meal_period") String mealPeriod,
        String notes) {

    public List<String> tasteTagsOrEmpty() {
        return tasteTags == null ? List.of() : tasteTags;
    }

    public List<String> avoidOrEmpty() {
        return avoidIngredients == null ? List.of() : avoidIngredients;
    }
}