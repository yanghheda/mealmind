package com.mealmind.biz.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.DecisionCardView;
import com.mealmind.biz.dto.DimView;

import org.springframework.stereotype.Component;

/**
 * 排序与合成（技术方案 §3.1）：综合分 = Σ(维度分 × 权重) × 10，展示 0–100；
 * 加权综合置信度 = Σ(维度置信度 × 权重)；分数接近（Δ≤2）时并列并标注「接近」。
 * 排在业务服务而不是 Agent 运行时，理由：依赖加权公式与候选集，属于确定性逻辑。
 */
@Component
public class ScoreRanker {

    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal TIE_THRESHOLD = new BigDecimal("2.0");

    private final AppProperties properties;

    public ScoreRanker(AppProperties properties) {
        this.properties = properties;
    }

    /** 单卡合成（card 事件即时展示用；并列标记要等全量分数，由 done 的最终列表给出）。 */
    public DecisionCardView toCard(RawCard raw, CandidateFact fact) {
        Scored scored = score(raw, fact);
        if (scored == null) {
            return null;
        }
        return view(scored, false);
    }

    public List<DecisionCardView> rank(List<RawCard> rawCards, Map<String, CandidateFact> facts) {
        List<Scored> scored = new ArrayList<>();
        for (RawCard raw : rawCards) {
            Scored item = score(raw, facts.get(raw.dishId()));
            if (item != null) {
                scored.add(item);
            }
        }
        scored.sort(Comparator
                .comparing(Scored::composite, Comparator.reverseOrder())
                .thenComparing(s -> s.raw().dishId()));

        boolean[] near = new boolean[scored.size()];
        for (int i = 1; i < scored.size(); i++) {
            BigDecimal gap = scored.get(i - 1).composite().subtract(scored.get(i).composite());
            if (gap.compareTo(TIE_THRESHOLD) <= 0) {
                near[i - 1] = true;
                near[i] = true;
            }
        }

        List<DecisionCardView> cards = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            cards.add(view(scored.get(i), near[i]));
        }
        return cards;
    }

    /** 综合分的纯函数形式（快照重建时按同一口径复算）。 */
    public BigDecimal composite(List<DimView> dims) {
        BigDecimal composite = BigDecimal.ZERO;
        Map<String, BigDecimal> weights = properties.getDecision().getWeights();
        for (DimView dim : dims) {
            composite = composite.add(dim.value().multiply(weights.getOrDefault(dim.dim(), BigDecimal.ZERO)));
        }
        return composite.multiply(TEN).setScale(2, RoundingMode.HALF_UP);
    }

    private Scored score(RawCard raw, CandidateFact fact) {
        if (fact == null || raw.dims() == null || raw.dims().isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> weights = properties.getDecision().getWeights();
        BigDecimal confidence = BigDecimal.ZERO;
        for (DimView dim : raw.dims()) {
            confidence = confidence.add(dim.confidence().multiply(weights.getOrDefault(dim.dim(), BigDecimal.ZERO)));
        }
        return new Scored(raw, fact, composite(raw.dims()), confidence.setScale(3, RoundingMode.HALF_UP));
    }

    private DecisionCardView view(Scored item, boolean near) {
        AgentCandidate payload = item.fact().payload();
        return new DecisionCardView(
                payload.dishId(),
                payload.name(),
                payload.merchantId(),
                payload.merchantName(),
                payload.price(),
                payload.imageUrl(),
                item.composite(),
                item.confidence(),
                item.raw().dims(),
                item.raw().mode(),
                near,
                item.fact().overBudget(),
                item.fact().specGroups());
    }

    private record Scored(RawCard raw, CandidateFact fact, BigDecimal composite, BigDecimal confidence) {
    }
}