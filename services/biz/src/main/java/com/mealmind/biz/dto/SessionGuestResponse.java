package com.mealmind.biz.dto;

import java.time.LocalDateTime;

/** 接口 1：创建游客身份响应。 */
public record SessionGuestResponse(String sessionId, String nickname, LocalDateTime loginAt) {
}
