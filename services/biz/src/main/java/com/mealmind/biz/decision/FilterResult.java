package com.mealmind.biz.decision;

import java.util.List;
import java.util.Map;

/** 约束引擎输出：候选事实 + 池子规模 + 各约束命中次数。 */
public record FilterResult(List<CandidateFact> candidates, int poolSize, Map<String, Integer> detail) {

    public int candidateCount() {
        return candidates.size();
    }
}