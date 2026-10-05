package com.mealmind.biz.controller;

import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.decision.DecisionService;
import com.mealmind.biz.dto.CreateRunRequest;
import com.mealmind.biz.dto.CreateRunResponse;
import com.mealmind.biz.dto.RunSnapshotResponse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 决策接口（技术方案 §4.1 接口 15/16/17）。
 * 接口 18（修正 / 换一批）随 M4 交互增强一并实现。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final DecisionService decisionService;

    public AgentController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** 接口 15：创建一次决策。 */
    @PostMapping("/runs")
    public ApiResponse<CreateRunResponse> createRun(@RequestBody CreateRunRequest request) {
        return ApiResponse.ok(decisionService.createRun(request));
    }

    /** 接口 16：SSE 订阅决策进度；断线重连带 Last-Event-ID 按 seq 补发。 */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return decisionService.stream(runId, lastEventId);
    }

    /** 接口 17：拉取快照（断线恢复对齐）。 */
    @GetMapping("/runs/{runId}")
    public ApiResponse<RunSnapshotResponse> snapshot(@PathVariable String runId) {
        return ApiResponse.ok(decisionService.snapshot(runId));
    }
}