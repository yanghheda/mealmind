package com.mealmind.biz.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 接口 4/5：分页商家列表响应。 */
public record MerchantListResponse(
        List<MerchantView> list,
        long total,
        @JsonProperty("hasMore") boolean hasMore) {
}
