package com.mealmind.biz.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.decision.AgentCandidate;
import com.mealmind.biz.decision.AgentClient;
import com.mealmind.biz.decision.CandidateFact;
import com.mealmind.biz.decision.ConstraintEngine;
import com.mealmind.biz.decision.DecisionProfile;
import com.mealmind.biz.decision.FilterResult;
import com.mealmind.biz.decision.RawCard;
import com.mealmind.biz.decision.ScoreRanker;
import com.mealmind.biz.dto.DecisionCardView;
import com.mealmind.biz.dto.DimView;
import com.mealmind.biz.entity.Badcase;
import com.mealmind.biz.entity.EvalBatch;
import com.mealmind.biz.entity.EvalMetric;
import com.mealmind.biz.entity.EvalResult;
import com.mealmind.biz.entity.GoldenCase;
import com.mealmind.biz.mapper.BadcaseMapper;
import com.mealmind.biz.mapper.EvalBatchMapper;
import com.mealmind.biz.mapper.EvalMetricMapper;
import com.mealmind.biz.mapper.EvalResultMapper;
import com.mealmind.biz.mapper.GoldenCaseMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 评测执行器（技术方案 §3.3）。
 * 对每条 golden_case：用期望约束做过滤 → 调 Agent 打分 → 比对预期 → 写结果；
 * 批次结束后汇总 8 项指标到 eval_metric，失败用例归档 badcase。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);
    private static final String MODE_GLOBAL = "GLOBAL";
    /** Top1 口味命中阈值：复用线上打分器 taste 维度口径（≥6.5 含泛辣与可接受的高一档辣）。 */
    private static final BigDecimal TASTE_HIT_THRESHOLD = new BigDecimal("6.5");

    private final ConstraintEngine constraintEngine;
    private final ScoreRanker scoreRanker;
    private final AgentClient agentClient;
    private final GoldenCaseMapper goldenCaseMapper;
    private final EvalBatchMapper evalBatchMapper;
    private final EvalResultMapper evalResultMapper;
    private final EvalMetricMapper evalMetricMapper;
    private final BadcaseMapper badcaseMapper;
    private final ObjectMapper objectMapper;

    public EvalService(ConstraintEngine constraintEngine,
                       ScoreRanker scoreRanker,
                       AgentClient agentClient,
                       GoldenCaseMapper goldenCaseMapper,
                       EvalBatchMapper evalBatchMapper,
                       EvalResultMapper evalResultMapper,
                       EvalMetricMapper evalMetricMapper,
                       BadcaseMapper badcaseMapper,
                       ObjectMapper objectMapper) {
        this.constraintEngine = constraintEngine;
        this.scoreRanker = scoreRanker;
        this.agentClient = agentClient;
        this.goldenCaseMapper = goldenCaseMapper;
        this.evalBatchMapper = evalBatchMapper;
        this.evalResultMapper = evalResultMapper;
        this.evalMetricMapper = evalMetricMapper;
        this.badcaseMapper = badcaseMapper;
        this.objectMapper = objectMapper;
    }

    /** 触发一次评测批次；limit 非空时等距抽样（同步执行，50 条用例约几分钟）。 */
    public EvalBatch runBatch(String triggeredBy, Integer limit) {
        List<GoldenCase> cases = goldenCaseMapper.selectList(
                new QueryWrapper<GoldenCase>().orderByAsc("created_at").orderByAsc("id"));
        if (cases.isEmpty()) {
            throw new IllegalStateException("golden_case 为空，请先导入评测用例");
        }
        cases = sample(cases, limit);

        // 上次异常中断（进程被杀 / 重启）会留下永久 RUNNING 的批次，先收口
        failStaleBatches();

        // id 列是 varchar(32)：与项目其它处一致，去掉 UUID 的横线
        String batchId = UUID.randomUUID().toString().replace("-", "");
        EvalBatch batch = new EvalBatch();
        batch.setId(batchId);
        batch.setRubricVersion("v1.2");
        batch.setCaseCount(cases.size());
        batch.setTriggeredBy(triggeredBy == null ? "system" : triggeredBy);
        batch.setStartedAt(LocalDateTime.now());
        batch.setStatus("RUNNING");
        evalBatchMapper.insert(batch);

        int hardPass = 0;
        int understandPass = 0;
        int sufficientPass = 0;
        int emptyCases = 0;
        int emptyPass = 0;
        int top1TastePass = 0;
        List<Long> latencies = new ArrayList<>();

        for (GoldenCase gc : cases) {
            CaseResult cr = runCase(gc);
            EvalResult er = new EvalResult();
            er.setBatchId(batchId);
            er.setCaseId(gc.getId());
            er.setPassed(cr.passed ? 1 : 0);
            er.setFailDetailJson(cr.failDetail);
            evalResultMapper.insert(er);

            if (cr.hardOk) hardPass++;
            if (cr.understandOk) understandPass++;
            if (cr.sufficientOk) sufficientPass++;
            if (cr.expectEmpty) {
                emptyCases++;
                if (cr.sufficientOk) emptyPass++;
            } else if (cr.top1TasteOk) {
                top1TastePass++;
            }
            latencies.add(cr.latencyMs);

            if (!cr.passed) {
                Badcase bc = new Badcase();
                bc.setCaseId(gc.getId());
                bc.setCategory(cr.failCategory);
                bc.setDetailJson(cr.failDetail);
                bc.setStatus("OPEN");
                bc.setCreatedAt(LocalDateTime.now());
                badcaseMapper.insert(bc);
            }
        }

        int total = cases.size();
        int nonEmpty = total - emptyCases;
        writeMetric(batchId, "hard_constraint_pass_rate", pct(hardPass, total), total);
        writeMetric(batchId, "understanding_accuracy", pct(understandPass, total), total);
        writeMetric(batchId, "candidate_sufficiency", pct(sufficientPass, total), total);
        writeMetric(batchId, "empty_correctness", pct(emptyPass, emptyCases), emptyCases);
        writeMetric(batchId, "top1_taste_match", pct(top1TastePass, nonEmpty), nonEmpty);
        writeMetric(batchId, "latency_p95_ms", BigDecimal.valueOf(p95(latencies)), total);

        batch.setStatus("DONE");
        batch.setFinishedAt(LocalDateTime.now());
        evalBatchMapper.updateById(batch);
        log.info("eval batch {} done, total={}", batchId, total);
        return batch;
    }

    /**
     * 等距抽样（含首尾）：用例按 id 排序后类型是分段的（BOUNDARY → NEGATIVE → STANDARD），
     * 直接取前 N 条会全是 BOUNDARY，测不到标准分支。id 排序同时保证抽样结果可复现
     * （created_at 是同批导入的同一秒，不足以定序）。
     */
    private List<GoldenCase> sample(List<GoldenCase> cases, Integer limit) {
        int size = cases.size();
        if (limit == null || limit <= 0 || limit >= size) {
            return cases;
        }
        if (limit == 1) {
            return List.of(cases.get(0));
        }
        List<GoldenCase> sampled = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            sampled.add(cases.get((int) Math.round((double) i * (size - 1) / (limit - 1))));
        }
        return sampled;
    }

    /** 把残留的 RUNNING 批次标记为 FAILED（批次是同步执行，此刻不可能有别的在跑）。 */
    private void failStaleBatches() {
        int stale = evalBatchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<EvalBatch>()
                .eq("status", "RUNNING")
                .set("status", "FAILED")
                .set("finished_at", LocalDateTime.now()));
        if (stale > 0) {
            log.warn("marked {} stale RUNNING batch(es) as FAILED", stale);
        }
    }

    private CaseResult runCase(GoldenCase gc) {
        JsonNode expected = parseJson(gc.getExpectedConstraints());
        DecisionProfile profile = toProfile(expected);
        boolean expectEmpty = expected.path("expectEmpty").asBoolean(false);

        long t0 = System.currentTimeMillis();
        FilterResult fr = constraintEngine.filter(profile, MODE_GLOBAL, null);
        Map<String, CandidateFact> facts = fr.candidates().stream()
                .collect(Collectors.toMap(f -> f.payload().dishId(), f -> f, (a, b) -> a, LinkedHashMap::new));
        List<AgentCandidate> candidates = fr.candidates().stream()
                .map(CandidateFact::payload).toList();

        AgentClient.ScoreResult result;
        try {
            // 评测指标不依赖推荐理由，关掉表达器：省掉约 85% 的生成式模型 token 消耗
            result = agentClient.score(gc.getInputText(), candidates, false);
        } catch (Exception ex) {
            return CaseResult.fail(gc.getId(), "agent_error", ex.getMessage());
        }
        long latency = System.currentTimeMillis() - t0;

        // 复核一律以业务侧候选事实为准，不信任 Agent 回显：
        // Agent 卡片不含 tags / deliveryMinutes，也不负责排序（排序与合成属于业务服务）。
        List<DecisionCardView> ranked = scoreRanker.rank(toRawCards(result.cards()), facts);

        boolean hardOk = checkHardConstraints(ranked, facts, expected);
        boolean understandOk = checkUnderstanding(result.profile(), expected);
        int minCandidates = expectEmpty ? 0 : expected.path("minCandidates").asInt(1);
        boolean sufficientOk = expectEmpty ? ranked.isEmpty() : ranked.size() >= minCandidates;
        boolean top1TasteOk = expectEmpty || (!ranked.isEmpty() && checkTop1Taste(ranked.get(0), expected));

        boolean passed = hardOk && understandOk && sufficientOk && (expectEmpty || top1TasteOk);
        String failCategory = !hardOk ? "hard_constraint"
                : !understandOk ? "understanding"
                : !sufficientOk ? "candidate_count"
                : !top1TasteOk ? "taste_mismatch" : null;
        String failDetail = passed ? null : buildFailDetail(gc, expected, ranked, result.profile(), failCategory);

        return new CaseResult(passed, hardOk, understandOk, sufficientOk, top1TasteOk,
                expectEmpty, failCategory, failDetail, latency);
    }

    /** Agent 回显的卡片 → 业务侧排序输入（只取 dishId / dims / mode）。 */
    private List<RawCard> toRawCards(JsonNode cards) {
        List<RawCard> raw = new ArrayList<>();
        for (JsonNode card : cards) {
            List<DimView> dims = new ArrayList<>();
            for (JsonNode dim : card.path("dims")) {
                dims.add(new DimView(dim.path("dim").asText(),
                        bd(dim.path("value")), bd(dim.path("confidence"))));
            }
            raw.add(new RawCard(card.path("dishId").asText(), dims, card.path("mode").asText()));
        }
        return raw;
    }

    private boolean checkHardConstraints(List<DecisionCardView> ranked, Map<String, CandidateFact> facts,
                                         JsonNode expected) {
        BigDecimal budgetMax = expected.has("budgetMax") ? bd(expected.path("budgetMax")) : null;
        List<String> avoidTags = toStringList(expected.path("avoidTags"));
        Integer maxDelivery = expected.has("maxDelivery") ? expected.path("maxDelivery").asInt() : null;
        for (DecisionCardView card : ranked) {
            CandidateFact fact = facts.get(card.dishId());
            if (fact == null) {
                // 回显了不在候选集里的菜品：约束无从保证，判违规
                return false;
            }
            AgentCandidate payload = fact.payload();
            // 预算浮动放行的「略超预算」次选是 PRD 5.3 显式允许的兜底，不算违规
            if (budgetMax != null && !fact.overBudget() && payload.price().compareTo(budgetMax) > 0) {
                return false;
            }
            if (maxDelivery != null && payload.deliveryMinutes() > maxDelivery) {
                return false;
            }
            for (AgentCandidate.AgentTag tag : payload.tags()) {
                if (avoidTags.contains(tag.tag())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkUnderstanding(JsonNode profile, JsonNode expected) {
        List<String> expectedTaste = toStringList(expected.path("tasteTags"));
        List<String> actualTaste = toStringList(profile.path("taste_tags"));
        if (!actualTaste.containsAll(expectedTaste)) {
            return false;
        }
        List<String> expectedAvoid = toStringList(expected.path("avoidTags"));
        List<String> actualAvoid = toStringList(profile.path("avoid_ingredients"));
        if (!actualAvoid.containsAll(expectedAvoid)) {
            return false;
        }
        if (expected.has("budgetMax")) {
            BigDecimal eb = bd(expected.path("budgetMax"));
            if (profile.path("budget_max").isMissingNode() || profile.path("budget_max").isNull()) {
                return false;
            }
            if (bd(profile.path("budget_max")).compareTo(eb) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Top1 口味命中：复用线上打分器的 taste 维度口径，而不是回显标签的精确匹配
     * （「辣」是泛辣度、菜品可比需求更辣一档，精确匹配会把这两种合理情况误判为失败）。
     */
    private boolean checkTop1Taste(DecisionCardView top, JsonNode expected) {
        if (toStringList(expected.path("tasteTags")).isEmpty()) {
            return true;
        }
        for (DimView dim : top.dims()) {
            if ("taste".equals(dim.dim())) {
                return dim.value().compareTo(TASTE_HIT_THRESHOLD) >= 0;
            }
        }
        return false;
    }

    private DecisionProfile toProfile(JsonNode expected) {
        return new DecisionProfile(
                toStringList(expected.path("tasteTags")),
                expected.has("budgetMax") ? bd(expected.path("budgetMax")) : null,
                toStringList(expected.path("avoidTags")),
                expected.has("maxDelivery") ? expected.path("maxDelivery").asInt() : null,
                expected.path("scene").asText(null),
                null,
                null);
    }

    private String buildFailDetail(GoldenCase gc, JsonNode expected, List<DecisionCardView> cards,
                                   JsonNode profile, String category) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("category", category);
        detail.put("input", gc.getInputText());
        detail.put("expected", expected);
        detail.put("actualProfile", profile);
        detail.put("cardCount", cards.size());
        detail.put("cards", objectMapper.valueToTree(cards));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception ex) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    private void writeMetric(String batchId, String key, BigDecimal value, int sample) {
        EvalMetric m = new EvalMetric();
        m.setBatchId(batchId);
        m.setMetricKey(key);
        m.setMetricValue(value);
        m.setSampleSize(sample);
        m.setComputedAt(LocalDateTime.now());
        evalMetricMapper.insert(m);
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
    }

    private static BigDecimal bd(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    private static BigDecimal pct(int numerator, int denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static long p95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, idx));
    }

    private record CaseResult(boolean passed, boolean hardOk, boolean understandOk,
                              boolean sufficientOk, boolean top1TasteOk, boolean expectEmpty,
                              String failCategory, String failDetail, long latencyMs) {

        static CaseResult fail(String caseId, String category, String message) {
            return new CaseResult(false, false, false, false, false, false,
                    category, "{\"error\":\"" + message + "\"}", 0);
        }
    }
}
