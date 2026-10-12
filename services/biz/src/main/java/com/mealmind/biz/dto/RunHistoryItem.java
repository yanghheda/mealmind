package com.mealmind.biz.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 会话历史单条（接口 23）：重进会话页时用于水合消息流的整轮还原视图。
 * 字段口径对齐 RunSnapshotResponse，未产出对应阶段的字段为 null / 空列表。
 */
public record RunHistoryItem(
        String runId,
        String inputText,
        String mode,
        String merchantId,
        String status,
        String degradeLevel,
        Integer latencyMs,
        /** REFINE 继承来源 run；非空表示该轮由「继续调整」发起。 */
        String parentRunId,
        /** 澄清状态 PENDING / ANSWERED / SKIPPED（未追问为 null）。 */
        String clarifyState,
        /** 待答澄清问题（clarify 事件原样载荷；未追问为 null）。 */
        JsonNode clarify,
        /** 结构化理解结果；未产出理解时为 null。 */
        JsonNode profile,
        /** filtered 事件视图（候选数 / 池子规模 / 各约束命中数）；未过滤时为 null。 */
        FilteredView filtered,
        /** 本次带入的长期偏好（id / type / content）。 */
        List<Map<String, Object>> appliedPreferences,
        List<DecisionCardView> cards) {
}