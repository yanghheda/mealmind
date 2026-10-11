package com.mealmind.biz.dto;

import java.util.List;

/** 接口 12：偏好记忆列表响应（默认只含 ACTIVE）。 */
public record PreferenceListResponse(List<PreferenceView> items) {
}