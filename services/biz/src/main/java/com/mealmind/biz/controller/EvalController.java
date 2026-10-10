package com.mealmind.biz.controller;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mealmind.biz.common.ApiResponse;
import com.mealmind.biz.entity.Badcase;
import com.mealmind.biz.entity.EvalBatch;
import com.mealmind.biz.entity.EvalMetric;
import com.mealmind.biz.entity.EvalResult;
import com.mealmind.biz.entity.GoldenCase;
import com.mealmind.biz.eval.EvalService;
import com.mealmind.biz.mapper.BadcaseMapper;
import com.mealmind.biz.mapper.EvalBatchMapper;
import com.mealmind.biz.mapper.EvalMetricMapper;
import com.mealmind.biz.mapper.EvalResultMapper;
import com.mealmind.biz.mapper.GoldenCaseMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 评测看板接口（PRD 4.9，技术方案 §3.3）。 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalBatchMapper evalBatchMapper;
    private final EvalResultMapper evalResultMapper;
    private final EvalMetricMapper evalMetricMapper;
    private final BadcaseMapper badcaseMapper;
    private final GoldenCaseMapper goldenCaseMapper;

    public EvalController(EvalService evalService,
                          EvalBatchMapper evalBatchMapper,
                          EvalResultMapper evalResultMapper,
                          EvalMetricMapper evalMetricMapper,
                          BadcaseMapper badcaseMapper,
                          GoldenCaseMapper goldenCaseMapper) {
        this.evalService = evalService;
        this.evalBatchMapper = evalBatchMapper;
        this.evalResultMapper = evalResultMapper;
        this.evalMetricMapper = evalMetricMapper;
        this.badcaseMapper = badcaseMapper;
        this.goldenCaseMapper = goldenCaseMapper;
    }

    /** 触发一次评测批次；limit 用于小样本抽样（等距取，覆盖三类用例），repeat 为稳定性复跑次数。 */
    @PostMapping("/batches")
    public ApiResponse<EvalBatch> runBatch(@RequestParam(required = false) String triggeredBy,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Integer repeat) {
        return ApiResponse.ok(evalService.runBatch(triggeredBy, limit, repeat));
    }

    /** 批次列表（最新在前）。 */
    @GetMapping("/batches")
    public ApiResponse<List<EvalBatch>> listBatches() {
        return ApiResponse.ok(evalBatchMapper.selectList(
                new QueryWrapper<EvalBatch>().orderByDesc("started_at")));
    }

    /** 批次详情（含指标快照）。 */
    @GetMapping("/batches/{batchId}")
    public ApiResponse<BatchDetail> getBatch(@PathVariable String batchId) {
        EvalBatch batch = evalBatchMapper.selectById(batchId);
        if (batch == null) {
            return ApiResponse.error(com.mealmind.biz.common.ErrorCode.RUN_NOT_FOUND);
        }
        List<EvalMetric> metrics = evalMetricMapper.selectList(
                new QueryWrapper<EvalMetric>().eq("batch_id", batchId));
        return ApiResponse.ok(new BatchDetail(batch, metrics));
    }

    /** 批次内逐条结果。 */
    @GetMapping("/batches/{batchId}/results")
    public ApiResponse<List<EvalResult>> listResults(@PathVariable String batchId) {
        return ApiResponse.ok(evalResultMapper.selectList(
                new QueryWrapper<EvalResult>().eq("batch_id", batchId)));
    }

    /** badcase 列表（最新在前）。 */
    @GetMapping("/badcases")
    public ApiResponse<List<Badcase>> listBadcases(
            @RequestParam(required = false) String status) {
        QueryWrapper<Badcase> qw = new QueryWrapper<Badcase>().orderByDesc("created_at");
        if (status != null && !status.isBlank()) {
            qw.eq("status", status);
        }
        return ApiResponse.ok(badcaseMapper.selectList(qw));
    }

    /** Golden 用例列表。 */
    @GetMapping("/golden-cases")
    public ApiResponse<List<GoldenCase>> listGoldenCases() {
        return ApiResponse.ok(goldenCaseMapper.selectList(
                new QueryWrapper<GoldenCase>().orderByAsc("created_at")));
    }

    public record BatchDetail(EvalBatch batch, List<EvalMetric> metrics) {
    }
}
