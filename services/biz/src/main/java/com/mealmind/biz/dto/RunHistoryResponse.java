package com.mealmind.biz.dto;

import java.util.List;

/** 接口 23 响应：最近 N 轮决策，按创建时间升序（旧 → 新）。 */
public record RunHistoryResponse(List<RunHistoryItem> items) {
}