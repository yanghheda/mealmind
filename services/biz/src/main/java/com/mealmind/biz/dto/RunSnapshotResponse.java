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
        String degradeLevel,
        /** 待答澄清问题（clarify 事件原样载荷；M4 W2），未追问时为 null。 */
        JsonNode clarify,
        /** 澄清状态 PENDING / ANSWERED / SKIPPED（M4 W2），未追问时为 null。 */
        String clarifyState) {
}