package com.mealmind.biz.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mealmind.biz.config.AppProperties;

import org.springframework.stereotype.Component;

/**
 * 单次决策成本计价（M4 W5，PRD §5.8 标价口径）。
 *
 * cost = qwen 输入 token/1e6*输入单价 + qwen 输出 token/1e6*输出单价 + Jev 调用次数*单次单价，
 * 保留 5 位小数。Jev 不逐次采 token：Agent 只上报调用次数，按实测常量（1018 入 / 90 出）折算日志口径。
 * 免费额度内实付为 0，看板需注明「标价口径」。
 */
@Component
public class CostCalculator {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    private final AppProperties properties;

    public CostCalculator(AppProperties properties) {
        this.properties = properties;
    }

    public BigDecimal compute(int inputTokens, int outputTokens, int jevCalls) {
        AppProperties.Cost cost = properties.getCost();
        BigDecimal qwen = BigDecimal.valueOf(inputTokens).divide(PER_MILLION)
                .multiply(cost.getQwenInputPerMillion())
                .add(BigDecimal.valueOf(outputTokens).divide(PER_MILLION)
                        .multiply(cost.getQwenOutputPerMillion()));
        BigDecimal jev = BigDecimal.valueOf(jevCalls).multiply(cost.getJevPerCall());
        return qwen.add(jev).setScale(5, RoundingMode.HALF_UP);
    }
}