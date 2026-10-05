package com.mealmind.biz.decision;

import java.math.BigDecimal;
import java.util.List;

/** 回填给 Agent 的打分输入：菜品标签 + 商家与时效事实（Agent 不查任何存储）。 */
public record AgentCandidate(
        String dishId,
        String name,
        String merchantId,
        String merchantName,
        BigDecimal price,
        String imageUrl,
        int deliveryMinutes,
        BigDecimal merchantRating,
        int dishMonthlySales,
        String categoryName,
        List<AgentTag> tags) {

    public record AgentTag(String tag, String tagType) {
    }
}