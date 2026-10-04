package com.mealmind.biz.dto;

import java.util.List;

/** 接口 7：商家菜单响应。 */
public record MenuResponse(String merchantId, List<MenuCategoryView> categories) {
}
