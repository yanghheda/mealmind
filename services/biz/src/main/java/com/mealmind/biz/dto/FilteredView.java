package com.mealmind.biz.dto;

/** filtered 事件：候选数与各约束命中次数（对齐 PRD 5.3 六条规则）。 */
public record FilteredView(int candidateCount, int poolSize, Object filteredDetail) {
}