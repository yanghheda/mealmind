package com.mealmind.biz.dto;

import java.util.List;

/** 菜单分类（接口 7）。 */
public record MenuCategoryView(String categoryId, String name, List<DishView> dishes) {
}
