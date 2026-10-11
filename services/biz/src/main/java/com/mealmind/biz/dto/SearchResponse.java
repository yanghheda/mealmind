package com.mealmind.biz.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 接口 21：搜索结果（商家与菜品分组）。
 * 商家沿用综合排序（可翻页，hasMore 指商家）；菜品按月售降序（同 page/pageSize 截断，dishTotal 为命中总数）。
 */
public record SearchResponse(
        String keyword,
        List<MerchantView> merchants,
        List<SearchDishView> dishes,
        long merchantTotal,
        @JsonProperty("hasMore") boolean hasMore,
        long dishTotal) {
}