package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.dto.SessionGuestResponse;
import com.mealmind.biz.dto.SessionMeResponse;
import com.mealmind.biz.service.SessionService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口（技术方案 §4.1 接口 1/2/3）。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 接口 1：创建游客身份（无需鉴权）。 */
    @PostMapping("/guest")
    public ApiResponse<SessionGuestResponse> guest() {
        return ApiResponse.ok(sessionService.createGuest());
    }

    /** 接口 2：查询当前登录态。 */
    @GetMapping("/me")
    public ApiResponse<SessionMeResponse> me() {
        return ApiResponse.ok(sessionService.me());
    }

    /** 接口 3：退出登录（清空该游客数据）。 */
    @DeleteMapping
    public ApiResponse<Void> logout() {
        sessionService.revoke();
        return ApiResponse.ok(null);
    }
}
