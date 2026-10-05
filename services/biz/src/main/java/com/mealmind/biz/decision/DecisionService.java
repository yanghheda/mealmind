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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.CreateRunRequest;
import com.mealmind.biz.dto.CreateRunResponse;
import com.mealmind.biz.dto.DecisionCardView;
import com.mealmind.biz.dto.DimView;
import com.mealmind.biz.dto.DoneView;
import com.mealmind.biz.dto.FilteredView;
import com.mealmind.biz.dto.RelaxOption;
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
import com.mealmind.biz.image.ImageUrlResolver;
import com.mealmind.biz.mapper.DecisionCandidateMapper;
import com.mealmind.biz.mapper.DecisionRunMapper;
import com.mealmind.biz.mapper.DecisionScoreMapper;
import com.mealmind.biz.mapper.DishMapper;
import com.mealmind.biz.mapper.DishSpecGroupMapper;
import com.mealmind.biz.mapper.DishSpecOptionMapper;
import com.mealmind.biz.mapper.MerchantMapper;
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
    private static final String STATUS_FAILED = "FAILED";
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
    private final RunHub runHub;
    private final AgentClient agentClient;
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
                           RunHub runHub,
                           AgentClient agentClient,
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
        this.runHub = runHub;
        this.agentClient = agentClient;
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
        DecisionRun run = new DecisionRun();
        run.setId(runId);
        run.setSessionId(sessionId);
        run.setInputText(text);
        run.setProfileJson("{}");
        run.setProfileFingerprint("");
        run.setMode(mode);
        run.setMerchantId(merchantId);
        run.setStatus(STATUS_RUNNING);
        run.setDegradeLevel("NORMAL");
        run.setRubricVersion(properties.getDecision().getRubricVersion());
        run.setCandidateCount(0);
        runMapper.insert(run);

        runHub.create(runId);
        executor.submit(() -> executeRun(runId, text, mode, merchantId));
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
                run.getRubricVersion());
    }

    // ------------------------------------------------------------------ 编排执行

    private void executeRun(String runId, String text, String mode, String merchantId) {
        RunStream stream = runHub.get(runId);
        if (stream == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicReference<DecisionProfile> profileRef = new AtomicReference<>();
        Map<String, CandidateFact> facts = new HashMap<>();
        List<RawCard> rawCards = new ArrayList<>();

        ScheduledFuture<?> timeout = watchdog.schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                log.warn("run timeout, runId={}", runId);
                stream.emit("error", Map.of(
                        "code", "RUN_TIMEOUT",
                        "message", "决策超时，请重试",
                        "retryable", true));
                markRunFailed(runId, startedAt);
                stream.finish();
            }
        }, properties.getDecision().getRunTimeoutMs(), TimeUnit.MILLISECONDS);

        try (InputStream body = agentClient.decide(runId, text, mode, merchantId)) {
            agentClient.readEvents(body, event -> {
                switch (event.name()) {
                    case "understand" -> handleUnderstand(runId, stream, mode, merchantId, event, profileRef, facts);
                    case "scoring" -> stream.emit("scoring", event.data());
                    case "card" -> handleCard(stream, event.data(), facts, rawCards);
                    case "reason.delta" -> stream.emit("reason.delta", event.data());
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
                    finishRun(runId, stream, startedAt, profileRef.get(), rawCards, facts);
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
            timeout.cancel(false);
        }
    }

    private void handleUnderstand(String runId, RunStream stream, String mode, String merchantId,
                                  AgentClient.AgentEvent event,
                                  AtomicReference<DecisionProfile> profileRef,
                                  Map<String, CandidateFact> facts) {
        JsonNode data = event.data();
        DecisionProfile profile = toProfile(data.path("profile"));
        profileRef.set(profile);
        updateRunUnderstanding(runId, data.path("profile"), fingerprint(profile));
        stream.emit("understand", data);

        FilterResult result = constraintEngine.filter(profile, mode, merchantId);
        for (CandidateFact fact : result.candidates()) {
            facts.put(fact.payload().dishId(), fact);
        }
        updateRunFiltered(runId, result);
        stream.emit("filtered", new FilteredView(result.candidateCount(), result.poolSize(), result.detail()));
        // 协作式编排：过滤节点由业务服务执行，回填后图继续打分
        agentClient.provideCandidates(runId,
                result.candidates().stream().map(CandidateFact::payload).toList());
    }

    private void handleCard(RunStream stream, JsonNode data, Map<String, CandidateFact> facts, List<RawCard> rawCards) {
        RawCard raw;
        try {
            raw = objectMapper.treeToValue(data, RawCard.class);
        } catch (Exception ex) {
            log.warn("invalid card payload: {}", data, ex);
            return;
        }
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
                           DecisionProfile profile, List<RawCard> rawCards, Map<String, CandidateFact> facts) {
        List<DecisionCardView> cards = rawCards.isEmpty() ? List.of() : scoreRanker.rank(rawCards, facts);
        long latencyMs = System.currentTimeMillis() - startedAt;
        BigDecimal costYuan = BigDecimal.ZERO.setScale(5, RoundingMode.HALF_UP);
        if (!cards.isEmpty()) {
            persistDecision(runId, cards);
        }
        markRunDone(runId, latencyMs, costYuan, cards.size());
        List<RelaxOption> relaxOptions = cards.isEmpty() ? relaxOptions(profile) : List.of();
        stream.emit("done", new DoneView(cards, latencyMs, costYuan,
                properties.getDecision().getRubricVersion(), relaxOptions));
        stream.finish();
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

    private void markRunDone(String runId, long latencyMs, BigDecimal costYuan, int candidateCount) {
        DecisionRun update = new DecisionRun();
        update.setId(runId);
        update.setStatus(STATUS_DONE);
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

    private void persistDecision(String runId, List<DecisionCardView> cards) {
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
                    specsByDish.getOrDefault(candidate.getDishId(), List.of())));
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
                card.mode(), true, card.overBudget(), card.specGroups());
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