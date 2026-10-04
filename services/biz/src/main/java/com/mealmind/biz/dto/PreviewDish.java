package com.mealmind.biz.dto;

import java.math.BigDecimal;

/** 商家列表卡片中的菜品预览条。 */
public record PreviewDish(String id, String name, String imageUrl, BigDecimal price) {
}
