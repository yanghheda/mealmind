package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.dto.PreferenceDeleteResponse;
import com.mealmind.biz.dto.PreferenceListResponse;
import com.mealmind.biz.dto.PreferenceUpsertRequest;
import com.mealmind.biz.dto.PreferenceView;
import com.mealmind.biz.service.PreferenceService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 偏好记忆接口（技术方案 §4.1 接口 12/13/14）。
 */
@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

    private final PreferenceService preferenceService;

    public PreferenceController(PreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /** 接口 12：记忆卡列表（默认只返回 ACTIVE）。 */
    @GetMapping
    public ApiResponse<PreferenceListResponse> list() {
        return ApiResponse.ok(new PreferenceListResponse(preferenceService.list()));
    }

    /** 接口 13：新增 / 恢复偏好（upsert）。 */
    @PostMapping
    public ApiResponse<PreferenceView> upsert(@RequestBody PreferenceUpsertRequest request) {
        return ApiResponse.ok(preferenceService.upsert(request.type(), request.content()));
    }

    /** 接口 14：删除偏好（墓碑，幂等）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<PreferenceDeleteResponse> delete(@PathVariable String id) {
        return ApiResponse.ok(preferenceService.delete(id));
    }
}