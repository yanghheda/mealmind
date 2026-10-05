package com.mealmind.biz.dto;

import java.util.List;

/** 接口 15 请求体：创建一次决策。 */
public record CreateRunRequest(
        String text,
        String mode,
        String merchantId,
        List<String> preferenceIds) {
}