package com.mealmind.biz.dto;

import java.util.List;

/**
 * 接口 18 响应体：REFINE 返回新 runId + streamUrl（cards/hasMore 为 null）；
 * NEXT_BATCH 返回原 runId + 本页卡片 + hasMore（streamUrl 为 null，无 SSE）。
 */
public record RefineResponse(
        String runId,
        String streamUrl,
        List<DecisionCardView> cards,
        Boolean hasMore) {
}