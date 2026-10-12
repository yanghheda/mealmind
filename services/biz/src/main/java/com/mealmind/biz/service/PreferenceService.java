package com.mealmind.biz.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.dto.PreferenceDeleteResponse;
import com.mealmind.biz.dto.PreferenceView;
import com.mealmind.biz.entity.Preference;
import com.mealmind.biz.mapper.PreferenceMapper;
import com.mealmind.biz.session.SessionContext;

import org.springframework.stereotype.Service;

/**
 * 偏好记忆（PRD 4.8 / 技术方案接口 12/13/14）：记忆卡 CRUD 与带入 run 的文本拼接。
 *
 * 偏好只由用户在「我的」页手动添加（接口 13），会话理解结果不再自动沉淀；
 * 删除为墓碑软删（IGNORED），同内容可手动重新添加恢复。
 */
@Service
public class PreferenceService {

    public static final String TYPE_TASTE = "TASTE";
    public static final String TYPE_BUDGET = "BUDGET";
    public static final String TYPE_AVOID = "AVOID";
    public static final String TYPE_SCENE = "SCENE";

    private static final Set<String> TYPES = Set.of(TYPE_TASTE, TYPE_BUDGET, TYPE_AVOID, TYPE_SCENE);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_IGNORED = "IGNORED";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final int CONTENT_MAX_LENGTH = 100;
    private static final BigDecimal BUDGET_MAX_YUAN = new BigDecimal("200");

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

    /** 历史回显：按 id 批量取行（不过滤 status，已墓碑化的行也要能还原当时的带入 chip）。 */
    public List<Preference> selectByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return preferenceMapper.selectList(new QueryWrapper<Preference>()
                .eq("session_id", SessionContext.sessionId())
                .in("id", ids));
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

    // ------------------------------------------------------------------ 工具

    private Preference find(String sessionId, String type, String content) {
        return preferenceMapper.selectOne(new QueryWrapper<Preference>()
                .eq("session_id", sessionId)
                .eq("type", type)
                .eq("content", content)
                .last("limit 1"));
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
}