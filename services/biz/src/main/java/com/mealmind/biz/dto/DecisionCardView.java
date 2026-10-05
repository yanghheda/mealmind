package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 推荐卡（card 事件与快照共用）。
 * 综合分、置信度、并列标记由业务服务合成（排序与合成属于业务服务）。
 */
public record DecisionCardView(
        String dishId,
        String name,
        String merchantId,
        String merchantName,
        BigDecimal price,
        String imageUrl,
        BigDecimal compositeScore,
        BigDecimal confidence,
        List<DimView> dims,
        String mode,
        boolean near,
        boolean overBudget,
        List<SpecGroupView> specGroups) {
}