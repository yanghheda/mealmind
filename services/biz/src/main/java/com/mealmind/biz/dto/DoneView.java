package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/** done 事件：最终排序结果与耗时成本（排序由业务服务完成）。 */
public record DoneView(
        List<DecisionCardView> cards,
        long latencyMs,
        BigDecimal costYuan,
        String rubricVersion,
        List<RelaxOption> relaxOptions) {
}