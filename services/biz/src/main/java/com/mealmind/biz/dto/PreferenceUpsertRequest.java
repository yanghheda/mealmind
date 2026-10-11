package com.mealmind.biz.dto;

/** 接口 13 请求体：新增 / 恢复一条偏好（同类型同内容已存在即复活为 ACTIVE）。 */
public record PreferenceUpsertRequest(String type, String content) {
}