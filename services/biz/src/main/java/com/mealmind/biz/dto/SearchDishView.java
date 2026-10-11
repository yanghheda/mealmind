package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 搜索结果中的菜品行（接口 21）。
 * tags 已按展示优先级排序并截断（RANK / SELLING / SPICY / FLAVOR / INGREDIENT / HEALTH，最多 3 个）。
 */
public record SearchDishView(
        String dishId,
        String name,
        String imageUrl,
        BigDecimal price,
        String merchantId,
        String merchantName,
        Integer deliveryMinutes,
        List<String> tags) {
}