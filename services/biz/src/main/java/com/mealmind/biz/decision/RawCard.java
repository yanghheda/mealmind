package com.mealmind.biz.decision;

import java.util.List;

import com.mealmind.biz.dto.DimView;

/** Agent card 事件在业务侧的解析视图（展示字段以业务侧候选事实为准，不信任回显）。 */
public record RawCard(String dishId, List<DimView> dims, String mode) {
}