package com.mealmind.biz.dto;

import java.math.BigDecimal;

/**
 * 单次决策成本摘要（M4 W5，看板）：数据源为最近 limit 条已完成的真实决策 run
 * （评测批次走 /internal/score，成本不进 cost_yuan），口径为 PRD §5.8 标价。
 */
public record CostSummaryResponse(
        BigDecimal avgCostYuan,
        long p95LatencyMs,
        int sampleSize,
        BigDecimal gateYuan,
        boolean overGate) {
}