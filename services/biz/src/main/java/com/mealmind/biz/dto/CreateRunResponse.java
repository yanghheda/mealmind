package com.mealmind.biz.dto;

/** 接口 15 响应体：runId 与 SSE 订阅地址。 */
public record CreateRunResponse(String runId, String streamUrl) {
}