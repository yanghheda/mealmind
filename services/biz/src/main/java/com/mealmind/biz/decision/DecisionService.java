package com.mealmind.biz.decision;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.ClarifyAnswersRequest;
import com.mealmind.biz.dto.CreateRunRequest;
import com.mealmind.biz.dto.CreateRunResponse;
import com.mealmind.biz.dto.DecisionCardView;
import com.mealmind.biz.dto.DimView;
import com.mealmind.biz.dto.DoneView;
import com.mealmind.biz.dto.FilteredView;
import com.mealmind.biz.dto.RelaxOption;
import com.mealmind.biz.dto.RefineRequest;
import com.mealmind.biz.dto.RefineResponse;
import com.mealmind.biz.dto.RunSnapshotResponse;
import com.mealmind.biz.dto.SpecGroupView;
import com.mealmind.biz.dto.SpecOptionView;
import com.mealmind.biz.entity.DecisionCandidate;
import com.mealmind.biz.entity.DecisionRun;
import com.mealmind.biz.entity.DecisionScore;
import com.mealmind.biz.entity.Dish;
import com.mealmind.biz.entity.DishSpecGroup;
import com.mealmind.biz.entity.DishSpecOption;
import com.mealmind.biz.entity.Merchant;
import com.mealmind.biz.entity.Preference;
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.DecisionCandidateMapper;
import com.mealmind.biz.mapper.DecisionRunMapper;
import com.mealmind.biz.mapper.DecisionScoreMapper;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishSpecGroupMapper;
import com.mealmind.biz.mapper.DishSpecOptionMapper;
import com.mealmind.biz.mapper.MerchantMapper;
import com.mealmind.biz.service.PreferenceService;
import com.mealmind.biz.session.SessionContext;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 决策编排（技术方案 §2.4 时序）：创建 run → 转发 SSE → 约束过滤（回填 Agent）→
 * 聚合打分 → 加权排序 → 落库。整轮超时由看门狗兜底；降级状态机在 M3 接入。
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private static final String MODE_GLOBAL = "GLOBAL";
    private static final String MODE_MERCHANT = "MERCHANT";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String DEGRADE_NORMAL = "NORMAL";
    private static final int TEXT_MAX_LENGTH = 200;

    private final DecisionRunMapper runMapper;
    private final DecisionCandidateMapper candidateMapper;
    private final DecisionScoreMapper scoreMapper;
    private final MerchantMapper merchantMapper;
    private final DishMapper dishMapper;
    private final DishSpecGroupMapper specGroupMapper;
    private final DishSpecOptionMapper specOptionMapper;
    private final ConstraintEngine constraintEngine;
    private final ScoreRanker scoreRanker;
    private final ScoreCache scoreCache;
    private final CostCalculator costCalculator;
    private final RunHub runHub;
    private final AgentClient agentClient;
    private final PreferenceService preferenceService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final ImageUrlResolver imageUrlResolver;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

    public DecisionService(DecisionRunMapper runMapper,
                           DecisionCandidateMapper candidateMapper,
                           DecisionScoreMapper scoreMapper,
                           MerchantMapper merchantMapper,
                           DishMapper dishMapper,
                           DishSpecGroupMapper specGroupMapper,
                           DishSpecOptionMapper specOptionMapper,
                           ConstraintEngine constraintEngine,
                           ScoreRanker scoreRanker,
                           ScoreCache scoreCache,
                           CostCalculator costCalculator,
                           RunHub runHub,
                           AgentClient agentClient,
                           PreferenceService preferenceService,
                           AppProperties properties,
                           ObjectMapper objectMapper,
                           StringRedisTemplate redis,
                           ImageUrlResolver imageUrlResolver) {
        this.runMapper = runMapper;
        this.candidateMapper = candidateMapper;
        this.scoreMapper = scoreMapper;
        this.merchantMapper = merchantMapper;
        this.dishMapper = dishMapper;
        this.specGroupMapper = specGroupMapper;
        this.specOptionMapper = specOptionMapper;
        this.constraintEngine = constraintEngine;
        this.scoreRanker = scoreRanker;
        this.scoreCache = scoreCache;
        this.costCalculator = costCalculator;
        this.runHub = runHub;
        this.agentClient = agentClient;
        this.preferenceService = preferenceService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.imageUrlResolver = imageUrlResolver;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
        watchdog.shutdownNow();
    }

    // ------------------------------------------------------------------ 接口 15

    public CreateRunResponse createRun(CreateRunRequest request) {
        String sessionId = SessionContext.sessionId();
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.EMPTY_TEXT);
        }
        if (text.length() > TEXT_MAX_LENGTH) {
            throw new BizException(ErrorCode.TEXT_TOO_LONG);
        }
        String mode = normalizeMode(request.mode());
        String merchantId = resolveMerchantId(mode, request.merchantId());

        // 幂等：同一会话 5 秒内相同 text + mode 视为重复，直接返回已有 runId
        LocalDateTime window = LocalDateTime.now().minusSeconds(properties.getDecision().getIdempotencySeconds());
        DecisionRun existing = runMapper.selectOne(new QueryWrapper<DecisionRun>()
                .eq("session_id", sessionId)
                .eq("mode", mode)
                .eq("input_text", text)
                .ge("created_at", window)
                .orderByDesc("created_at")
                .last("limit 1"));
        if (existing != null) {
            return new CreateRunResponse(existing.getId(), streamUrl(existing.getId()));
        }

        Long running = runMapper.selectCount(new QueryWrapper<DecisionRun>()
                .eq("session_id", sessionId)
                .eq("status", STATUS_RUNNING));
        if (running != null && running >= properties.getDecision().getMaxConcurrentRuns()) {
            throw new BizException(ErrorCode.TOO_MANY_RUNS);
        }

        String runId = UUID.randomUUID().toString().replace("-", "");
        // 长期偏好带入：文本拼接在理解前完成（用户原话在前，本次所说预算可覆盖偏好默认值）
        List<Preference> appliedPrefs = preferenceService.selectForRun(request.preferenceIds());
        String agentText = preferenceService.augmentText(text, appliedPrefs);
        DecisionRun run = new DecisionRun();
        run.setId(runId);
        run.setSessionId(sessionId);
        run.setInputText(text);
        run.setProfileJson("{}");
        run.setProfileFingerprint("");
        run.setMode(mode);
        run.setMerchantId(merchantId);
        run.setStatus(STATUS_RUNNING);
        run.setDegradeLevel(DEGRADE_NORMAL);
        run.setRubricVersion(properties.getDecision().getRubricVersion());
        run.setCandidateCount(0);
        run.setAppliedPreferenceIds(toJson(appliedPrefs.stream().map(Preference::getId).toList()));
        runMapper.insert(run);

        runHub.create(runId);
        executor.submit(() -> executeRun(runId, sessionId, agentText, mode, merchantId, appliedPrefs, null));
        return new CreateRunResponse(runId, streamUrl(runId));
    }

    // ------------------------------------------------------------------ 接口 16

    public SseEmitter stream(String runId, String lastEventId) {
        DecisionRun run = requireOwnedRun(runId);
        long lastSeq = parseSeq(lastEventId);
        RunStream stream = runHub.get(runId);
        if (stream != null) {
            return stream.subscribe(lastSeq);
        }
        // 进程内容器已过期（或服务重启）：按 Redis 进度流回放后关闭，客户端再以快照兜底
        return replayEmitter(runId, lastSeq, run.getStatus());
    }

    private SseEmitter replayEmitter(String runId, long lastSeq, String status) {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            List<String> raw = redis.opsForList().range(properties.getRedisPrefix() + "run:" + runId + ":events", 0, -1);
            if (raw != null) {
                for (String json : raw) {
                    JsonNode item = objectMapper.readTree(json);
                    if (item.path("seq").asLong() > lastSeq) {
                        emitter.send(SseEmitter.event()
                                .id(item.path("seq").asText())
                                .name(item.path("event").asText())
                                .data(item.path("data"), MediaType.APPLICATION_JSON));
                    }
                }
            }
            if (STATUS_RUNNING.equals(status)) {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "code", "RUN_INTERRUPTED",
                        "message", "决策已中断，请重试",
                        "retryable", true), MediaType.APPLICATION_JSON));
            }
        } catch (Exception ex) {
            log.warn("replay failed, runId={}", runId, ex);
        }
        emitter.complete();
        return emitter;
    }

    // ------------------------------------------------------------------ 接口 17

    public RunSnapshotResponse snapshot(String runId) {
        DecisionRun run = requireOwnedRun(runId);
        List<DecisionCandidate> candidates = candidateMapper.selectList(new QueryWrapper<DecisionCandidate>()
                .eq("run_id", runId)
                .orderByAsc("rank_no"));
        List<DecisionCardView> cards = rebuildCards(run, candidates);
        RunStream stream = runHub.get(runId);
        return new RunSnapshotResponse(
                run.getId(),
                run.getStatus(),
                readJson(run.getProfileJson()),
                readJson(run.getFilteredDetail()),
                cards,
                stream == null ? 0 : stream.lastSeq(),
                run.getLatencyMs(),
                run.getCostYuan(),
                run.getRubricVersion(),
                run.getDegradeLevel(),
                clarifyPayload(run),
                run.getClarifyState());
    }

    /** 快照中的待答澄清载荷：仅落过 clarify 的 run 返回（未追问为 null）。 */
    private JsonNode clarifyPayload(DecisionRun run) {
        String json = run.getClarifyJson();
        return json == null || json.isBlank() ? null : readJson(json);
    }

    // ------------------------------------------------------------------ M4 W2 澄清

    /** 提交澄清回答或整轮跳过：不在待答态（含已被 Agent 超时收口）返回 409。 */
    public void answerClarify(String runId, ClarifyAnswersRequest request) {
        DecisionRun run = requireOwnedRun(runId);
        if (!"PENDING".equals(run.getClarifyState())) {
            throw new BizException(ErrorCode.CLARIFY_NOT_PENDING);
        }
        try {
            agentClient.answers(runId, request.answersOrEmpty(), request.skipped());
        } catch (AgentClient.ClarifyGoneException ex) {
            throw new BizException(ErrorCode.CLARIFY_NOT_PENDING);
        }
    }

    // ------------------------------------------------------------------ 接口 18 修正 / 换一批

    /**
     * 修正（REFINE：新文本 = 原话 + 本次修正，新建继承 run 走完整理解）与
     * 换一批（NEXT_BATCH：同 run 读已落库候选重建卡片，零模型成本、无 SSE、秒回）。
     */
    public RefineResponse refine(String runId, RefineRequest request) {
        DecisionRun parent = requireOwnedRun(runId);
        if (!STATUS_DONE.equals(parent.getStatus()) && !STATUS_DEGRADED.equals(parent.getStatus())) {
            throw new BizException(ErrorCode.RUN_NOT_DONE);
        }
        String type = request.type() == null ? "" : request.type().trim().toUpperCase();
        return switch (type) {
            case "NEXT_BATCH" -> nextBatch(parent, request);
            case "REFINE" -> refineRun(parent, request);
            default -> throw new BizException(ErrorCode.INVALID_PARAM);
        };
    }

    /** 换一批：按 rank_no 读候选、跳过已展示、重建卡片（带理由与规格），不新建 run、不调模型。 */
    private RefineResponse nextBatch(DecisionRun parent, RefineRequest request) {
        Set<String> excluded = request.excludeDishIds() == null
                ? Set.of() : new HashSet<>(request.excludeDishIds());
        int pageSize = request.pageSize() == null ? 4 : Math.max(1, Math.min(20, request.pageSize()));
        List<DecisionCandidate> rest = candidateMapper.selectList(new QueryWrapper<DecisionCandidate>()
                        .eq("run_id", parent.getId())
                        .orderByAsc("rank_no")).stream()
                .filter(candidate -> !excluded.contains(candidate.getDishId()))
                .toList();
        List<DecisionCandidate> page = rest.size() > pageSize ? rest.subList(0, pageSize) : rest;
        List<DecisionCardView> cards = rebuildCards(parent, page);
        return new RefineResponse(parent.getId(), null, cards, rest.size() > page.size());
    }

    /** 修正：继承原 run 的 mode / merchantId / 带入偏好，`parent_run_id` 落库供追溯。 */
    private RefineResponse refineRun(DecisionRun parent, RefineRequest request) {
        String refineText = request.text() == null ? "" : request.text().trim();
        if (refineText.isEmpty()) {
            throw new BizException(ErrorCode.EMPTY_TEXT);
        }
        if (refineText.length() > TEXT_MAX_LENGTH) {
            throw new BizException(ErrorCode.TEXT_TOO_LONG);
        }
        String text = parent.getInputText() + "；" + refineText;
        // 幂等：同一父 run + 同一拼接文本 5 秒内视为重复提交，直接返回已有 run
        LocalDateTime window = LocalDateTime.now().minusSeconds(properties.getDecision().getIdempotencySeconds());
        DecisionRun existing = runMapper.selectOne(new QueryWrapper<DecisionRun>()
                .eq("session_id", parent.getSessionId())
                .eq("parent_run_id", parent.getId())
                .eq("input_text", text)
                .ge("created_at", window)
                .orderByDesc("created_at")
                .last("limit 1"));
        if (existing != null) {
            return new RefineResponse(existing.getId(), streamUrl(existing.getId()), null, null);
        }
        Long running = runMapper.selectCount(new QueryWrapper<DecisionRun>()
                .eq("session_id", parent.getSessionId())
                .eq("status", STATUS_RUNNING));
        if (running != null && running >= properties.getDecision().getMaxConcurrentRuns()) {
            throw new BizException(ErrorCode.TOO_MANY_RUNS);
        }

        List<Preference> appliedPrefs = preferenceService.selectForRun(parsePreferenceIds(parent.getAppliedPreferenceIds()));
        String agentText = preferenceService.augmentText(text, appliedPrefs);
        String runId = UUID.randomUUID().toString().replace("-", "");
        DecisionRun run = new DecisionRun();
        run.setId(runId);
        run.setSessionId(parent.getSessionId());
        run.setInputText(text);
        run.setProfileJson("{}");
        run.setProfileFingerprint("");
        run.setMode(parent.getMode());
        run.setMerchantId(parent.getMerchantId());
        run.setStatus(STATUS_RUNNING);
        run.setDegradeLevel(DEGRADE_NORMAL);
        run.setRubricVersion(properties.getDecision().getRubricVersion());
        run.setCandidateCount(0);
        run.setAppliedPreferenceIds(toJson(appliedPrefs.stream().map(Preference::getId).toList()));
        run.setParentRunId(parent.getId());
        runMapper.insert(run);

        runHub.create(runId);
        DecisionProfile parentProfile = toProfile(readJson(parent.getProfileJson()));
        executor.submit(() -> executeRun(runId, parent.getSessionId(), agentText, parent.getMode(),
                parent.getMerchantId(), appliedPrefs, parentProfile));
        return new RefineResponse(runId, streamUrl(runId), null, null);
    }

    /** 父 run 带入的偏好 id（REFINE 继承基线）：`[]` = 当时未带入，历史数据缺失按 null（默认全选 ACTIVE）。 */
    private List<String> parsePreferenceIds(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    ids.add(item.asText());
                }
            }
            return ids;
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 编排执行

    private void executeRun(String runId, String sessionId, String text, String mode, String merchantId,
                            List<Preference> appliedPrefs, DecisionProfile parentProfile) {
        RunStream stream = runHub.get(runId);
        if (stream == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<DecisionProfile> profileRef = new AtomicReference<>();
        AtomicReference<String> degradeLevelRef = new AtomicReference<>(DEGRADE_NORMAL);
        AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>();
        Map<String, CandidateFact> facts = new HashMap<>();
        List<RawCard> rawCards = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>();
        Set<String> pendingCacheWrites = new HashSet<>();
        AtomicInteger cachedHits = new AtomicInteger();
        AtomicInteger candidateTotal = new AtomicInteger();
        AtomicReference<JsonNode> usageRef = new AtomicReference<>();

        scheduleWatchdog(timeoutRef, finished, runId, stream, startedAt,
                properties.getDecision().getRunTimeoutMs());
        // 澄清门控（W2）：首答含疑问时不执行过滤，由二次 understand 或 clarify.resolved 触发
        Runnable startFilter = () -> filterAndBackfill(runId, stream, mode, merchantId, profileRef.get(),
                facts, rawCards, reasons, pendingCacheWrites, cachedHits, candidateTotal);

        try (InputStream body = agentClient.decide(runId, text, mode, merchantId)) {
            agentClient.readEvents(body, event -> {
                switch (event.name()) {
                    case "understand" -> handleUnderstand(runId, sessionId, stream, event, appliedPrefs, profileRef, startFilter,
                            parentProfile);
                    case "clarify" -> {
                        // 落库待答问题并换档：整轮超时 = 原超时 + 澄清等待窗口（用户思考不占用打分预算）
                        updateRunClarify(runId, event.data());
                        stream.emit("clarify", event.data());
                        scheduleWatchdog(timeoutRef, finished, runId, stream, startedAt,
                                properties.getDecision().getRunTimeoutMs() + properties.getDecision().getClarifyTimeoutMs());
                    }
                    case "clarify.resolved" -> {
                        boolean skipped = event.data().path("skipped").asBoolean(true);
                        updateRunClarifyState(runId, skipped ? "SKIPPED" : "ANSWERED");
                        stream.emit("clarify.resolved", event.data());
                        scheduleWatchdog(timeoutRef, finished, runId, stream, startedAt,
                                properties.getDecision().getRunTimeoutMs());
                        // 跳过 / 超时没有二次 understand，由这里恢复过滤；回答路径由二次 understand 触发
                        if (skipped) {
                            startFilter.run();
                        }
                    }
                    // 进度把缓存命中的部分补回来，前端看到的仍是全部候选的进度
                    case "scoring" -> stream.emit("scoring", mergeScoringProgress(event.data(), cachedHits.get(), candidateTotal.get()));
                    case "card" -> handleCard(stream, event.data(), facts, rawCards);
                    case "reason.delta" -> {
                        reasons.put(event.data().path("dishId").asText(), event.data().path("text").asText(""));
                        stream.emit("reason.delta", event.data());
                    }
                    // 用量上报（M4 W5）：整轮只发一次，转发给前端并留待 done 前计价
                    case "usage" -> {
                        usageRef.set(event.data());
                        stream.emit("usage", event.data());
                    }
                    case "degraded" -> {
                        degradeLevelRef.set(event.data().path("level").asText(DEGRADE_NORMAL));
                        stream.emit("degraded", event.data());
                    }
                    case "error" -> {
                        failed.set(true);
                        stream.emit("error", event.data());
                        markRunFailed(runId, startedAt);
                    }
                    default -> log.debug("ignored agent event, runId={}, event={}", runId, event.name());
                }
            });
            if (finished.get()) {
                return;
            }
            if (finished.compareAndSet(false, true)) {
                if (failed.get()) {
                    stream.finish();
                } else {
                    finishRun(runId, stream, startedAt, profileRef.get(), rawCards, facts, degradeLevelRef.get(),
                            reasons, pendingCacheWrites, usageRef.get());
                }
            }
        } catch (Exception ex) {
            log.warn("run failed, runId={}, reason={}", runId, ex.getMessage());
            if (finished.compareAndSet(false, true)) {
                stream.emit("error", Map.of(
                        "code", "AGENT_UNAVAILABLE",
                        "message", "智能推荐暂不可用，请重试",
                        "retryable", true));
                markRunFailed(runId, startedAt);
                stream.finish();
            }
        } finally {
            cancelWatchdog(timeoutRef);
        }
    }

    /** 看门狗排期（W2 支持换档）：排新档前先取消旧档，避免澄清期间被原档误杀。 */
    private void scheduleWatchdog(AtomicReference<ScheduledFuture<?>> timeoutRef, AtomicBoolean finished,
                                  String runId, RunStream stream, long startedAt, long delayMs) {
        ScheduledFuture<?> previous = timeoutRef.getAndSet(watchdog.schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                log.warn("run timeout, runId={}", runId);
                stream.emit("error", Map.of(
                        "code", "RUN_TIMEOUT",
                        "message", "决策超时，请重试",
                        "retryable", true));
                markRunFailed(runId, startedAt);
                stream.finish();
            }
        }, delayMs, TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelWatchdog(AtomicReference<ScheduledFuture<?>> timeoutRef) {
        ScheduledFuture<?> current = timeoutRef.get();
        if (current != null) {
            current.cancel(false);
        }
    }

    private void handleUnderstand(String runId, String sessionId, RunStream stream, AgentClient.AgentEvent event,
                                  List<Preference> appliedPrefs,
                                  AtomicReference<DecisionProfile> profileRef,
                                  Runnable startFilter,
                                  DecisionProfile parentProfile) {
        JsonNode data = event.data();
        DecisionProfile understood = toProfile(data.path("profile"));
        // REFINE 抗抖动：修正 run 用原 profile 补齐缺失字段，过滤/打分/指纹与下发的 profile 保持同一份
        DecisionProfile profile = mergeWithParent(parentProfile, understood);
        profileRef.set(profile);
        String fingerprint = fingerprint(profile);
        updateRunUnderstanding(runId, profile == null ? data.path("profile") : objectMapper.valueToTree(profile),
                fingerprint);
        stream.emit("understand", withAppliedPreferences(withProfile(data, profile), appliedPrefs));

        // 自动沉淀（PRD 4.8）：忌口立即记忆、口味/预算/场景跨 run 重复才记忆；命中新写/升级时下发可撤销提示
        PreferenceService.ObserveResult observed = preferenceService.observe(sessionId, runId, profile, appliedPrefs);
        if (!observed.items().isEmpty()) {
            stream.emit("preference.saved", Map.of("items", observed.items(), "promoted", observed.promoted()));
        }

        // 澄清门控（W2）：首答含疑问时只转发不执行过滤，避免 profile 在用户回答前被用于过滤与打分
        if (data.path("questions").size() > 0) {
            return;
        }
        startFilter.run();
    }

    /** 门控通过后执行：约束过滤 → 候选落盘 → 缓存命中回放 → 未命中部分回填 Agent 打分。 */
    private void filterAndBackfill(String runId, RunStream stream, String mode, String merchantId,
                                   DecisionProfile profile, Map<String, CandidateFact> facts, List<RawCard> rawCards,
                                   Map<String, String> reasons, Set<String> pendingCacheWrites,
                                   AtomicInteger cachedHits, AtomicInteger candidateTotal) {
        if (profile == null) {
            return;
        }
        FilterResult result = constraintEngine.filter(profile, mode, merchantId);
        for (CandidateFact fact : result.candidates()) {
            facts.put(fact.payload().dishId(), fact);
        }
        updateRunFiltered(runId, result);
        stream.emit("filtered", new FilteredView(result.candidateCount(), result.poolSize(), result.detail()));
        candidateTotal.set(result.candidateCount());

        // 打分缓存（cache-aside）：命中的菜品直接复用，未命中的才交给 Agent 打分
        List<AgentCandidate> all = result.candidates().stream().map(CandidateFact::payload).toList();
        Map<String, ScoreCache.Entry> hits = scoreCache.getMany(fingerprint(profile), all.stream()
                .map(candidate -> candidate.dishId()).toList());

        List<AgentCandidate> misses = new ArrayList<>();
        for (AgentCandidate candidate : all) {
            ScoreCache.Entry entry = hits.get(candidate.dishId());
            if (entry == null) {
                misses.add(candidate);
                pendingCacheWrites.add(candidate.dishId());
                continue;
            }
            cachedHits.incrementAndGet();
            RawCard raw = new RawCard(candidate.dishId(), entry.dims(), entry.mode());
            emitCard(stream, raw, facts, rawCards);
            if (entry.reason() != null && !entry.reason().isBlank()) {
                reasons.put(candidate.dishId(), entry.reason());
                stream.emit("reason.delta", Map.of("dishId", candidate.dishId(), "text", entry.reason()));
            }
        }
        log.info("run {} cache hit {}/{} candidates, scoring {} via agent",
                runId, hits.size(), all.size(), misses.size());
        // 全部命中时 Agent 不会产出打分进度，这里补一条，避免前端进度停在「逐菜打分」
        if (misses.isEmpty() && !all.isEmpty()) {
            stream.emit("scoring", Map.of("done", all.size(), "total", all.size(),
                    "mode", cachedMode(hits, all)));
        }
        // 协作式编排：过滤节点由业务服务执行，回填后图继续打分（全部命中时不回填，图直接结束）
        agentClient.provideCandidates(runId, misses);
    }

    /** understand 事件追加 appliedPreferences（接口 16 向后兼容新增字段），供前端渲染带入 chips。 */
    private JsonNode withAppliedPreferences(JsonNode data, List<Preference> applied) {
        ObjectNode copy = data.isObject() ? ((ObjectNode) data).deepCopy() : objectMapper.createObjectNode();
        copy.set("appliedPreferences", objectMapper.valueToTree(preferenceService.appliedPayload(applied)));
        return copy;
    }

    /** understand 事件里的 profile 替换为补齐后的结果，保证前端看到的就是过滤/打分实际使用的那份。 */
    private JsonNode withProfile(JsonNode data, DecisionProfile profile) {
        if (profile == null) {
            return data;
        }
        ObjectNode copy = data.isObject() ? ((ObjectNode) data).deepCopy() : objectMapper.createObjectNode();
        copy.set("profile", objectMapper.valueToTree(profile));
        return copy;
    }

    /**
     * REFINE 抗抖动（接口 18）：修正文本 = 原话 + 修正，理解结果字段数不应少于原 profile；
     * 少则用原 profile 补齐——口味/忌口取并集，其余空缺回填原值，避免模型抖动导致候选与缓存错位。
     */
    private DecisionProfile mergeWithParent(DecisionProfile parent, DecisionProfile current) {
        if (parent == null || current == null || populatedCount(current) >= populatedCount(parent)) {
            return current;
        }
        return new DecisionProfile(
                union(parent.tasteTagsOrEmpty(), current.tasteTagsOrEmpty()),
                current.budgetMax() != null ? current.budgetMax() : parent.budgetMax(),
                union(parent.avoidOrEmpty(), current.avoidOrEmpty()),
                current.maxDeliveryMin() != null ? current.maxDeliveryMin() : parent.maxDeliveryMin(),
                current.scene() != null ? current.scene() : parent.scene(),
                current.mealPeriod() != null ? current.mealPeriod() : parent.mealPeriod(),
                current.notes() != null ? current.notes() : parent.notes());
    }

    private int populatedCount(DecisionProfile profile) {
        int count = 0;
        if (!profile.tasteTagsOrEmpty().isEmpty()) {
            count++;
        }
        if (profile.budgetMax() != null) {
            count++;
        }
        if (!profile.avoidOrEmpty().isEmpty()) {
            count++;
        }
        if (profile.maxDeliveryMin() != null) {
            count++;
        }
        if (profile.scene() != null && !profile.scene().isBlank()) {
            count++;
        }
        if (profile.mealPeriod() != null && !profile.mealPeriod().isBlank()) {
            count++;
        }
        if (profile.notes() != null && !profile.notes().isBlank()) {
            count++;
        }
        return count;
    }

    private List<String> union(List<String> base, List<String> extra) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(base);
        merged.addAll(extra);
        return new ArrayList<>(merged);
    }

    private String cachedMode(Map<String, ScoreCache.Entry> hits, List<AgentCandidate> all) {
        for (AgentCandidate candidate : all) {
            ScoreCache.Entry entry = hits.get(candidate.dishId());
            if (entry != null && entry.mode() != null) {
                return entry.mode();
            }
        }
        return "RULE";
    }

    /** 把 Agent 的 card 事件写成 RawCard 并即时下发（缓存命中路径复用同一出口）。 */
    private void handleCard(RunStream stream, JsonNode data, Map<String, CandidateFact> facts, List<RawCard> rawCards) {
        RawCard raw;
        try {
            raw = objectMapper.treeToValue(data, RawCard.class);
        } catch (Exception ex) {
            log.warn("invalid card payload: {}", data, ex);
            return;
        }
        emitCard(stream, raw, facts, rawCards);
    }

    private void emitCard(RunStream stream, RawCard raw, Map<String, CandidateFact> facts, List<RawCard> rawCards) {
        CandidateFact fact = facts.get(raw.dishId());
        if (fact == null) {
            log.warn("card for unknown dish, dishId={}", raw.dishId());
            return;
        }
        rawCards.add(raw);
        DecisionCardView card = scoreRanker.toCard(raw, fact);
        if (card != null) {
            stream.emit("card", card);
        }
    }

    private void finishRun(String runId, RunStream stream, long startedAt,
                           DecisionProfile profile, List<RawCard> rawCards, Map<String, CandidateFact> facts,
                           String degradeLevel, Map<String, String> reasons, Set<String> pendingCacheWrites,
                           JsonNode usage) {
        List<DecisionCardView> cards = rawCards.isEmpty() ? List.of() : scoreRanker.rank(rawCards, facts);
        long latencyMs = System.currentTimeMillis() - startedAt;
        // 成本计量（M4 W5）：Agent 用量事件缺失（旧版容器 / 异常路径）按 0 计
        int inputTokens = usage == null ? 0 : usage.path("inputTokens").asInt(0);
        int outputTokens = usage == null ? 0 : usage.path("outputTokens").asInt(0);
        int decisionCalls = usage == null ? 0 : usage.path("decisionCalls").asInt(0);
        boolean estimated = usage != null && usage.path("estimated").asBoolean(false);
        BigDecimal costYuan = costCalculator.compute(inputTokens, outputTokens, decisionCalls);
        if (!cards.isEmpty()) {
            persistDecision(runId, cards, reasons);
        }
        markRunDone(runId, latencyMs, costYuan, cards.size(), degradeLevel);
        // 打分缓存只回写「全程由主通道打分且未降级」的一轮（缓存污染修复）：
        //   · degradeLevel != NORMAL：本轮混入了被迫回退的规则分（PARTIAL / FULL）；
        //   · decisionCalls <= 0：本轮根本没走主通道（强制 RULE / 未配置主通道）。
        // 这类规则分一旦入缓存，后续 AUTO 轮会在 TTL 内命中并回放、不再调主通道，
        // 但 run 仍报 NORMAL，降级被静默掩盖（评测看板 DEGRADE_COUNT 也会少计）。
        // 规则分本地即可重算、零主通道成本，不回写没有损失。
        if (DEGRADE_NORMAL.equals(degradeLevel) && decisionCalls > 0) {
            writeScoreCache(profile, rawCards, pendingCacheWrites, reasons);
        }
        List<RelaxOption> relaxOptions = cards.isEmpty() ? relaxOptions(profile) : List.of();
        stream.emit("done", new DoneView(cards, latencyMs, costYuan,
                new DoneView.CostBreakdown(inputTokens, outputTokens, decisionCalls, estimated),
                properties.getDecision().getRubricVersion(), relaxOptions, degradeLevel));
        stream.finish();
    }

    /** 回写本次真正打过分的菜品；理由缺失的条目不写，避免后续命中时缺理由。 */
    private void writeScoreCache(DecisionProfile profile, List<RawCard> rawCards,
                                 Set<String> pendingWrites, Map<String, String> reasons) {
        if (profile == null || pendingWrites.isEmpty()) {
            return;
        }
        Map<String, ScoreCache.Entry> entries = new LinkedHashMap<>();
        for (RawCard raw : rawCards) {
            if (!pendingWrites.contains(raw.dishId())) {
                continue;
            }
            String reason = reasons.get(raw.dishId());
            if (reason == null || reason.isBlank()) {
                continue;
            }
            entries.put(raw.dishId(), new ScoreCache.Entry(raw.dims(), raw.mode(), reason));
        }
        scoreCache.putMany(fingerprint(profile), entries);
    }

    /** 把缓存命中数并入 Agent 的进度事件，前端看到的 total 始终是全部候选。 */
    private Map<String, Object> mergeScoringProgress(JsonNode data, int cachedHits, int candidateTotal) {
        int total = candidateTotal > 0 ? candidateTotal : data.path("total").asInt();
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("done", Math.min(data.path("done").asInt() + cachedHits, total));
        merged.put("total", total);
        merged.put("mode", data.path("mode").asText("RULE"));
        return merged;
    }

    // ------------------------------------------------------------------ 落库

    private void updateRunUnderstanding(String runId, JsonNode profile, String fingerprint) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setProfileJson(profile.toString());
        update.setProfileFingerprint(fingerprint);
        runMapper.updateById(update);
    }

    private void updateRunFiltered(String runId, FilterResult result) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setCandidateCount(result.candidateCount());
        update.setFilteredDetail(toJson(result.detail()));
        runMapper.updateById(update);
    }

    /** 落库待答澄清问题（clarify 事件原样载荷），供快照恢复追问卡（W2）。 */
    private void updateRunClarify(String runId, JsonNode clarify) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setClarifyJson(clarify.toString());
        update.setClarifyState("PENDING");
        runMapper.updateById(update);
    }

    private void updateRunClarifyState(String runId, String state) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setClarifyState(state);
        runMapper.updateById(update);
    }

    private void markRunDone(String runId, long latencyMs, BigDecimal costYuan, int candidateCount, String degradeLevel) {
        boolean degraded = degradeLevel != null && !DEGRADE_NORMAL.equals(degradeLevel);
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        // 有降级的 run 单独记为 DEGRADED，便于评测看板统计降级次数（技术方案 §7.3）
        update.setStatus(degraded ? STATUS_DEGRADED : STATUS_DONE);
        update.setDegradeLevel(degradeLevel == null ? DEGRADE_NORMAL : degradeLevel);
        update.setLatencyMs((int) latencyMs);
        update.setCostYuan(costYuan);
        update.setCandidateCount(candidateCount);
        runMapper.updateById(update);
    }

    private void markRunFailed(String runId, long startedAt) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setStatus(STATUS_FAILED);
        update.setLatencyMs((int) (System.currentTimeMillis() - startedAt));
        runMapper.updateById(update);
    }

    /** 候选与维度分落库；推荐理由一并写入（M4 W3），供换一批与快照恢复回填。 */
    private void persistDecision(String runId, List<DecisionCardView> cards, Map<String, String> reasons) {
        int rank = 1;
        for (DecisionCardView card : cards) {
            DecisionCandidate candidate = new DecisionCandidate();
            candidate.setRunId(runId);
            candidate.setDishId(card.dishId());
            candidate.setCompositeScore(card.compositeScore());
            candidate.setCompositeConfidence(card.confidence());
            candidate.setRankNo(rank++);
            candidate.setIsShown(1);
            candidate.setIsAddedToCart(0);
            String reason = card.reason() != null ? card.reason() : reasons.get(card.dishId());
            candidate.setReason(reason == null || reason.isBlank() ? null : reason);
            candidateMapper.insert(candidate);
            for (DimView dim : card.dims()) {
                DecisionScore score = new DecisionScore();
                score.setRunId(runId);
                score.setDishId(card.dishId());
                score.setDim(dim.dim());
                score.setValue(dim.value());
                score.setConfidence(dim.confidence());
                score.setScoreMode(card.mode());
                scoreMapper.insert(score);
            }
        }
    }

    // ------------------------------------------------------------------ 快照重建

    private List<DecisionCardView> rebuildCards(DecisionRun run, List<DecisionCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> dishIds = candidates.stream().map(DecisionCandidate::getDishId).toList();
        Map<String, Dish> dishes = new HashMap<>();
        for (Dish dish : dishMapper.selectByIds(dishIds)) {
            dishes.put(dish.getId(), dish);
        }
        Map<String, Merchant> merchants = new HashMap<>();
        for (Merchant merchant : merchantMapper.selectList(null)) {
            merchants.put(merchant.getId(), merchant);
        }
        Map<String, List<DimView>> dimsByDish = new HashMap<>();
        Map<String, String> modeByDish = new HashMap<>();
        for (DecisionScore score : scoreMapper.selectList(new QueryWrapper<DecisionScore>().eq("run_id", run.getId()))) {
            dimsByDish.computeIfAbsent(score.getDishId(), k -> new ArrayList<>())
                    .add(new DimView(score.getDim(), score.getValue(), score.getConfidence()));
            modeByDish.putIfAbsent(score.getDishId(), score.getScoreMode());
        }
        Map<String, List<SpecGroupView>> specsByDish = loadSpecGroups(dishIds);
        DecisionProfile profile = toProfile(readJson(run.getProfileJson()));

        List<DecisionCardView> cards = new ArrayList<>();
        for (DecisionCandidate candidate : candidates) {
            Dish dish = dishes.get(candidate.getDishId());
            if (dish == null) {
                continue;
            }
            Merchant merchant = merchants.get(dish.getMerchantId());
            List<DimView> dims = dimsByDish.getOrDefault(candidate.getDishId(), List.of());
            BigDecimal price = displayPrice(dish, specsByDish.getOrDefault(candidate.getDishId(), List.of()));
            boolean overBudget = profile != null && profile.budgetMax() != null
                    && price.compareTo(profile.budgetMax()) > 0;
            cards.add(new DecisionCardView(
                    dish.getId(),
                    dish.getName(),
                    dish.getMerchantId(),
                    merchant == null ? "" : merchant.getName(),
                    price,
                    imageUrlResolver.dishImage(merchant == null ? null : merchant.getCategory()),
                    candidate.getCompositeScore(),
                    candidate.getCompositeConfidence(),
                    dims,
                    modeByDish.getOrDefault(candidate.getDishId(), "RULE"),
                    false,
                    overBudget,
                    specsByDish.getOrDefault(candidate.getDishId(), List.of()),
                    candidate.getReason()));
        }
        markNearFlags(cards);
        return cards;
    }

    private void markNearFlags(List<DecisionCardView> cards) {
        for (int i = 1; i < cards.size(); i++) {
            BigDecimal prev = cards.get(i - 1).compositeScore();
            BigDecimal current = cards.get(i).compositeScore();
            if (prev != null && current != null && prev.subtract(current).compareTo(new BigDecimal("2.0")) <= 0) {
                DecisionCardView a = cards.get(i - 1);
                DecisionCardView b = cards.get(i);
                cards.set(i - 1, withNear(a));
                cards.set(i, withNear(b));
            }
        }
    }

    private DecisionCardView withNear(DecisionCardView card) {
        return new DecisionCardView(card.dishId(), card.name(), card.merchantId(), card.merchantName(),
                card.price(), card.imageUrl(), card.compositeScore(), card.confidence(), card.dims(),
                card.mode(), true, card.overBudget(), card.specGroups(), card.reason());
    }

    private BigDecimal displayPrice(Dish dish, List<SpecGroupView> specGroups) {
        BigDecimal price = dish.getPrice();
        for (SpecGroupView group : specGroups) {
            BigDecimal groupMin = group.options().stream()
                    .map(SpecOptionView::extraPrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            price = price.add(groupMin);
        }
        return price;
    }

    private Map<String, List<SpecGroupView>> loadSpecGroups(List<String> dishIds) {
        List<DishSpecGroup> groups = specGroupMapper.selectList(
                new QueryWrapper<DishSpecGroup>().in("dish_id", dishIds).orderByAsc("sort_no"));
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<String> groupIds = groups.stream().map(DishSpecGroup::getId).toList();
        Map<String, List<DishSpecOption>> optionsByGroup = new HashMap<>();
        for (DishSpecOption option : specOptionMapper.selectList(
                new QueryWrapper<DishSpecOption>().in("group_id", groupIds).orderByAsc("sort_no"))) {
            optionsByGroup.computeIfAbsent(option.getGroupId(), k -> new ArrayList<>()).add(option);
        }
        Map<String, List<SpecGroupView>> result = new LinkedHashMap<>();
        for (DishSpecGroup group : groups) {
            List<SpecOptionView> options = optionsByGroup.getOrDefault(group.getId(), List.of()).stream()
                    .map(o -> new SpecOptionView(o.getId(), o.getOptionName(), o.getExtraPrice(),
                            o.getIsDefault() != null && o.getIsDefault() != 0))
                    .toList();
            result.computeIfAbsent(group.getDishId(), k -> new ArrayList<>())
                    .add(new SpecGroupView(group.getId(), group.getGroupName(),
                            group.getIsRequired() != null && group.getIsRequired() != 0, options));
        }
        return result;
    }

    // ------------------------------------------------------------------ 工具

    private DecisionRun requireOwnedRun(String runId) {
        DecisionRun run = runMapper.selectById(runId);
        if (run == null || !run.getSessionId().equals(SessionContext.sessionId())) {
            throw new BizException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_GLOBAL;
        }
        String upper = mode.trim().toUpperCase();
        if (!MODE_GLOBAL.equals(upper) && !MODE_MERCHANT.equals(upper)) {
            throw new BizException(ErrorCode.INVALID_MODE);
        }
        return upper;
    }

    /** 本店模式必须带合法 merchantId；全局模式返回 null。 */
    private String resolveMerchantId(String mode, String merchantId) {
        if (!MODE_MERCHANT.equals(mode)) {
            return null;
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new BizException(ErrorCode.MERCHANT_ID_REQUIRED);
        }
        if (merchantMapper.selectById(merchantId) == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchantId;
    }

    private String streamUrl(String runId) {
        return "/api/agent/runs/" + runId + "/stream";
    }

    private long parseSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<RelaxOption> relaxOptions(DecisionProfile profile) {
        List<RelaxOption> options = new ArrayList<>();
        if (profile != null && profile.budgetMax() != null) {
            BigDecimal relaxed = profile.budgetMax().multiply(new BigDecimal("1.1"))
                    .setScale(0, RoundingMode.UP);
            options.add(new RelaxOption("BUDGET", "预算放宽到 " + relaxed + " 元",
                    "预算放宽到" + relaxed + "元"));
        }
        if (profile != null && profile.maxDeliveryMin() != null) {
            int relaxed = Math.min(60, profile.maxDeliveryMin() + 10);
            options.add(new RelaxOption("DELIVERY", "送达时效放宽到 " + relaxed + " 分钟",
                    "送达时间放宽到" + relaxed + "分钟"));
        }
        if (profile != null && !profile.avoidOrEmpty().isEmpty()) {
            options.add(new RelaxOption("AVOID", "去掉忌口条件", "不限制忌口，重新推荐"));
        }
        options.add(new RelaxOption("GENERIC", "不限条件，随便看看", "不限口味预算，随便推荐一点"));
        return options;
    }

    private DecisionProfile toProfile(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, DecisionProfile.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 需求指纹：结构化偏好归一化（口味/忌口排序、预算取整）后哈希，保证语序不同命中同一指纹。 */
    private String fingerprint(DecisionProfile profile) {
        if (profile == null) {
            return "";
        }
        List<String> tastes = new ArrayList<>(profile.tasteTagsOrEmpty());
        List<String> avoids = new ArrayList<>(profile.avoidOrEmpty());
        tastes.sort(Comparator.naturalOrder());
        avoids.sort(Comparator.naturalOrder());
        String normalized = "taste=" + String.join(",", tastes)
                + "|budget=" + (profile.budgetMax() == null ? "-" : profile.budgetMax().setScale(0, RoundingMode.HALF_UP))
                + "|avoid=" + String.join(",", avoids)
                + "|delivery=" + (profile.maxDeliveryMin() == null ? "-" : profile.maxDeliveryMin())
                + "|scene=" + (profile.scene() == null ? "-" : profile.scene());
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}