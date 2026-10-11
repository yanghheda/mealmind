package com.mealmind.biz.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.decision.DecisionProfile;
import com.mealmind.biz.dto.PreferenceDeleteResponse;
import com.mealmind.biz.dto.PreferenceView;
import com.mealmind.biz.entity.Preference;
import com.mealmind.biz.mapper.PreferenceMapper;
import com.mealmind.biz.session.SessionContext;

import org.springframework.stereotype.Service;

/**
 * 偏好记忆（PRD 4.8 / 技术方案接口 12/13/14）：记忆卡 CRUD、自动沉淀、带入 run 的文本拼接。
 *
 * 自动沉淀规则：明确说出的忌口立即写入 ACTIVE；口味/预算/场景同一内容跨 run 重复出现
 * （seen_count ≥ 2）才升级 ACTIVE，首次仅记 PENDING；IGNORED 为墓碑，同内容不再自动写入，
 * 直到用户手动重新添加。本次 run 已带入的偏好内容一律跳过，防止自我强化。
 */
@Service
public class PreferenceService {

    public static final String TYPE_TASTE = "TASTE";
    public static final String TYPE_BUDGET = "BUDGET";
    public static final String TYPE_AVOID = "AVOID";
    public static final String TYPE_SCENE = "SCENE";

    private static final Set<String> TYPES = Set.of(TYPE_TASTE, TYPE_BUDGET, TYPE_AVOID, TYPE_SCENE);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_IGNORED = "IGNORED";
    private static final String SOURCE_AUTO = "AUTO";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final int CONTENT_MAX_LENGTH = 100;
    private static final int PROMOTE_THRESHOLD = 2;
    private static final BigDecimal BUDGET_MAX_YUAN = new BigDecimal("200");

    /**
     * 忌口白名单：与 Agent {@code _AVOID_VOCAB} 同表，去掉「辣椒」——其关键词含「辣的」，
     * 避免「想吃辣」被误记忆为忌口辣椒。
     */
    private static final Set<String> AVOID_WHITELIST = Set.of(
            "香菜", "葱", "蒜", "花生", "虾", "鱼", "蛋", "乳制品", "豆制品", "菌菇",
            "内脏", "米", "面", "粉", "蔬菜", "牛肉", "猪肉", "鸡肉", "鸭肉", "蟹");

    /** 文本拼接顺序：口味 → 忌口 → 预算 → 场景（与设计示例「微辣；不吃香菜；预算 30 元以内」一致）。 */
    private static final List<String> TEXT_ORDER = List.of(TYPE_TASTE, TYPE_AVOID, TYPE_BUDGET, TYPE_SCENE);

    private final PreferenceMapper preferenceMapper;

    public PreferenceService(PreferenceMapper preferenceMapper) {
        this.preferenceMapper = preferenceMapper;
    }

    // ------------------------------------------------------------------ 接口 12/13/14

    /** 接口 12：记忆卡列表（默认只返回 ACTIVE；IGNORED 墓碑与 PENDING 观察项不出现）。 */
    public List<PreferenceView> list() {
        return preferenceMapper.selectList(new QueryWrapper<Preference>()
                        .eq("session_id", SessionContext.sessionId())
                        .eq("status", STATUS_ACTIVE)
                        .orderByDesc("created_at"))
                .stream()
                .map(PreferenceView::of)
                .toList();
    }

    /**
     * 接口 13：新增 / 复活偏好。同 (session, type, content) 已存在（含 PENDING、IGNORED 墓碑）
     * 即置为 ACTIVE/MANUAL，同时承担「手动恢复墓碑」职责。
     */
    public PreferenceView upsert(String type, String content) {
        String normalizedType = normalizeType(type);
        String normalizedContent = normalizeContent(normalizedType, content);
        String sessionId = SessionContext.sessionId();

        Preference existing = find(sessionId, normalizedType, normalizedContent);
        if (existing != null) {
            existing.setStatus(STATUS_ACTIVE);
            existing.setSource(SOURCE_MANUAL);
            preferenceMapper.updateById(existing);
            return PreferenceView.of(existing);
        }
        Preference row = newPreference(sessionId, normalizedType, normalizedContent, SOURCE_MANUAL, STATUS_ACTIVE);
        row.setLastSeenRunId(null);
        preferenceMapper.insert(row);
        return PreferenceView.of(row);
    }

    /** 接口 14：删除（幂等墓碑，不物理删除；同内容不再自动写入直到手动重新添加）。 */
    public PreferenceDeleteResponse delete(String id) {
        Preference row = preferenceMapper.selectById(id);
        if (row == null || !row.getSessionId().equals(SessionContext.sessionId())) {
            throw new BizException(ErrorCode.PREFERENCE_NOT_FOUND);
        }
        row.setStatus(STATUS_IGNORED);
        preferenceMapper.updateById(row);
        return new PreferenceDeleteResponse(row.getId(), STATUS_IGNORED);
    }

    // ------------------------------------------------------------------ 带入 run

    /**
     * 本次 run 带入的偏好：{@code null} → 默认全选 ACTIVE；{@code []} → 全部不带入；
     * 不属于本会话或非 ACTIVE 的 id 静默忽略。返回顺序即文本拼接顺序（口味 → 忌口 → 预算 → 场景）。
     */
    public List<Preference> selectForRun(List<String> preferenceIds) {
        if (preferenceIds != null && preferenceIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<Preference> query = new QueryWrapper<Preference>()
                .eq("session_id", SessionContext.sessionId())
                .eq("status", STATUS_ACTIVE);
        if (preferenceIds != null) {
            query.in("id", preferenceIds);
        }
        List<Preference> rows = preferenceMapper.selectList(query);
        rows.sort(Comparator.comparingInt((Preference row) -> TEXT_ORDER.indexOf(row.getType()))
                .thenComparing(Preference::getCreatedAt));
        return rows;
    }

    /**
     * 长期偏好以文本形式拼在用户原话之后（理解前拼接，不做 profile 后合并——否则过滤/打分输入
     * 与指纹、缓存键错位）。用户原话在前保证「本次说的预算覆盖偏好默认预算」：理解器按模式
     * 优先级取第一个匹配，故预算措辞避开「预算 N」形式（{@code 预算\s*(\d)} 会越过用户原话被先命中）。
     */
    public String augmentText(String text, List<Preference> applied) {
        if (applied.isEmpty()) {
            return text;
        }
        String joined = applied.stream().map(PreferenceService::phrase).collect(Collectors.joining("；"));
        return text + "（长期偏好：" + joined + "）";
    }

    private static String phrase(Preference preference) {
        return switch (preference.getType()) {
            case TYPE_AVOID -> "不吃" + preference.getContent();
            case TYPE_BUDGET -> "日常控制在 " + preference.getContent() + " 元以内";
            default -> preference.getContent();
        };
    }

    /** understand 事件附加的 appliedPreferences 载荷（接口 16 向后兼容新增字段），供前端渲染带入 chips。 */
    public List<Map<String, Object>> appliedPayload(List<Preference> applied) {
        List<Map<String, Object>> payload = new ArrayList<>(applied.size());
        for (Preference row : applied) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("type", row.getType());
            item.put("content", row.getContent());
            payload.add(item);
        }
        return payload;
    }

    // ------------------------------------------------------------------ 自动沉淀

    /**
     * 在 DecisionService 收到 understand 时调用：返回本次新写/升级的条目（用于 preference.saved 提示）。
     * 方法级同步：同一会话的多个 run 可能并发 observe，避免同内容重复插入。
     */
    public synchronized ObserveResult observe(String sessionId, String runId, DecisionProfile profile,
                                              List<Preference> applied) {
        if (profile == null) {
            return ObserveResult.EMPTY;
        }
        Set<String> appliedContents = applied.stream().map(Preference::getContent).collect(Collectors.toSet());
        List<PreferenceView> items = new ArrayList<>();
        boolean promoted = false;

        // 1) 明确说出的忌口 → 立即写入（白名单 + 排除本次带入，防自我强化）
        for (String raw : dedupe(profile.avoidOrEmpty())) {
            String content = raw == null ? null : raw.trim();
            if (content == null || content.isEmpty() || !AVOID_WHITELIST.contains(content)
                    || appliedContents.contains(content)) {
                continue;
            }
            Preference row = find(sessionId, TYPE_AVOID, content);
            if (row != null && STATUS_IGNORED.equals(row.getStatus())) {
                continue;
            }
            if (row == null) {
                Preference inserted = newPreference(sessionId, TYPE_AVOID, content, SOURCE_AUTO, STATUS_ACTIVE);
                inserted.setLastSeenRunId(runId);
                preferenceMapper.insert(inserted);
                items.add(PreferenceView.of(inserted));
            } else if (!STATUS_ACTIVE.equals(row.getStatus())) {
                row.setStatus(STATUS_ACTIVE);
                row.setSource(SOURCE_AUTO);
                row.setLastSeenRunId(runId);
                preferenceMapper.updateById(row);
                items.add(PreferenceView.of(row));
            } else {
                touch(row, runId);
            }
        }

        // 2) 口味 / 预算 / 场景 → 同一内容跨 run 重复出现才升级 ACTIVE
        for (Observed candidate : inferred(profile)) {
            if (appliedContents.contains(candidate.content())) {
                continue;
            }
            Preference row = find(sessionId, candidate.type(), candidate.content());
            if (row == null) {
                Preference inserted = newPreference(sessionId, candidate.type(), candidate.content(),
                        SOURCE_AUTO, STATUS_PENDING);
                inserted.setLastSeenRunId(runId);
                preferenceMapper.insert(inserted);
                continue;
            }
            // 墓碑优先：同内容被用户删除过则整条跳过；同 run 已观测过不重复计数
            if (STATUS_IGNORED.equals(row.getStatus()) || runId.equals(row.getLastSeenRunId())) {
                continue;
            }
            int seenCount = (row.getSeenCount() == null ? 1 : row.getSeenCount()) + 1;
            boolean promote = STATUS_PENDING.equals(row.getStatus()) && seenCount >= PROMOTE_THRESHOLD;
            row.setSeenCount(seenCount);
            row.setLastSeenRunId(runId);
            if (promote) {
                row.setStatus(STATUS_ACTIVE);
                row.setSource(SOURCE_AUTO);
            }
            preferenceMapper.updateById(row);
            if (promote) {
                items.add(PreferenceView.of(row));
                promoted = true;
            }
        }
        return new ObserveResult(items, promoted);
    }

    /** observe 结果：items 为本次新写/升级的条目（前端渲染可撤销提示），promoted 表示是否由 PENDING 升级而来。 */
    public record ObserveResult(List<PreferenceView> items, boolean promoted) {
        public static final ObserveResult EMPTY = new ObserveResult(List.of(), false);
    }

    private record Observed(String type, String content) {
    }

    private List<Observed> inferred(DecisionProfile profile) {
        List<Observed> result = new ArrayList<>();
        for (String raw : dedupe(profile.tasteTagsOrEmpty())) {
            addObserved(result, TYPE_TASTE, raw);
        }
        if (profile.budgetMax() != null) {
            BigDecimal budget = profile.budgetMax().setScale(0, RoundingMode.HALF_UP);
            if (budget.compareTo(BigDecimal.ONE) >= 0 && budget.compareTo(BUDGET_MAX_YUAN) <= 0) {
                addObserved(result, TYPE_BUDGET, budget.toPlainString());
            }
        }
        addObserved(result, TYPE_SCENE, profile.scene());
        return result;
    }

    private void addObserved(List<Observed> target, String type, String rawContent) {
        if (rawContent == null) {
            return;
        }
        String content = rawContent.trim();
        if (!content.isEmpty() && content.length() <= CONTENT_MAX_LENGTH
                && target.stream().noneMatch(item -> item.type().equals(type) && item.content().equals(content))) {
            target.add(new Observed(type, content));
        }
    }

    // ------------------------------------------------------------------ 工具

    private Preference find(String sessionId, String type, String content) {
        return preferenceMapper.selectOne(new QueryWrapper<Preference>()
                .eq("session_id", sessionId)
                .eq("type", type)
                .eq("content", content)
                .last("limit 1"));
    }

    /** 已生效偏好再次出现：只更新跨 run 计数与来源 run（计数语义为「跨 run 观测次数」）。 */
    private void touch(Preference row, String runId) {
        if (runId.equals(row.getLastSeenRunId())) {
            return;
        }
        row.setSeenCount((row.getSeenCount() == null ? 1 : row.getSeenCount()) + 1);
        row.setLastSeenRunId(runId);
        preferenceMapper.updateById(row);
    }

    private Preference newPreference(String sessionId, String type, String content, String source, String status) {
        Preference row = new Preference();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setSessionId(sessionId);
        row.setType(type);
        row.setContent(content);
        row.setSource(source);
        row.setStatus(status);
        row.setSeenCount(1);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private String normalizeType(String type) {
        String upper = type == null ? "" : type.trim().toUpperCase();
        if (!TYPES.contains(upper)) {
            throw new BizException(ErrorCode.INVALID_PREFERENCE_TYPE);
        }
        return upper;
    }

    private String normalizeContent(String type, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (TYPE_BUDGET.equals(type)) {
            // 预算归一化为整数元字符串（容忍「30 元」等输入）
            String digits = trimmed.replaceAll("[^0-9]", "");
            if (digits.isEmpty() || new BigDecimal(digits).compareTo(BUDGET_MAX_YUAN) > 0) {
                throw new BizException(ErrorCode.INVALID_PARAM);
            }
            return digits;
        }
        if (trimmed.isEmpty() || trimmed.length() > CONTENT_MAX_LENGTH) {
            throw new BizException(ErrorCode.INVALID_PARAM);
        }
        return trimmed;
    }

    private List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}