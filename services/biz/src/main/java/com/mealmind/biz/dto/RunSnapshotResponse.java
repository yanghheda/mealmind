package com.mealmind.biz.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 接口 17 快照：断线恢复时先对齐快照，再带 Last-Event-ID 续订流。 */
public record RunSnapshotResponse(
        String runId,
        String status,
        JsonNode profile,
        JsonNode filteredDetail,
        List<DecisionCardView> cards,
        int lastSeq,
        Integer latencyMs,
        java.math.BigDecimal costYuan,
        String rubricVersion,
        String degradeLevel) {
}