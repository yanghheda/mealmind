package com.mealmind.biz.dto;

import java.time.LocalDateTime;

/** 接口 2：查询当前登录态响应。 */
public record SessionMeResponse(
        String sessionId,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt,
        String status) {
}
