package com.mealmind.biz.dto;

import java.math.BigDecimal;
import java.util.List;

/** done 事件：最终排序结果与耗时成本（排序由业务服务完成）。 */
public record DoneView(
        List<DecisionCardView> cards,
        long latencyMs,
        BigDecimal costYuan,
        CostBreakdown costBreakdown,
        String rubricVersion,
        List<RelaxOption> relaxOptions,
        String degradeLevel) {

    /** 成本构成（M4 W5）：token 用量与 Jev 调用次数；estimated=true 表示 token 为估算值。 */
    public record CostBreakdown(int inputTokens, int outputTokens, int jevCalls, boolean estimated) {
    }
}