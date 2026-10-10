package com.mealmind.biz.decision;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.DimView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 打分缓存（技术方案 §6.3）：业务服务侧 cache-aside，Agent 容器不接 Redis。
 *
 * 键 `mealmind:score:{rubricVersion}:{profileFingerprint}:{dishId}`，按菜品粒度复用。
 * 缓存条目含维度分、打分通道与推荐理由——理由也是模型产物，只缓存分数起不到
 * 「不重复计费」的作用。
 *
 * 缓存不可用时全部按未命中处理：Redis 故障不影响决策正确性（§8.4）。
 */
@Component
public class ScoreCache {

    private static final Logger log = LoggerFactory.getLogger(ScoreCache.class);

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ScoreCache(StringRedisTemplate redis, AppProperties properties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 单条缓存内容（与 Agent 的 card / reason 输出一一对应）。 */
    public record Entry(List<DimView> dims, String mode, String reason) {
    }

    /** 批量读；未命中或 Redis 异常都不在返回结果里。 */
    public Map<String, Entry> getMany(String fingerprint, List<String> dishIds) {
        if (fingerprint == null || fingerprint.isBlank() || dishIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Entry> found = new LinkedHashMap<>();
        try {
            List<String> keys = dishIds.stream().map(dishId -> key(fingerprint, dishId)).toList();
            List<String> raw = redis.opsForValue().multiGet(keys);
            if (raw == null) {
                return Map.of();
            }
            for (int i = 0; i < dishIds.size(); i++) {
                String json = raw.get(i);
                if (json != null) {
                    found.put(dishIds.get(i), objectMapper.readValue(json, Entry.class));
                }
            }
        } catch (Exception ex) {
            log.warn("score cache read failed, treated as miss: {}", ex.getMessage());
            return Map.of();
        }
        if (!found.isEmpty()) {
            log.info("score cache hit {}/{} (fingerprint={})", found.size(), dishIds.size(), fingerprint);
        }
        return found;
    }

    /** 批量写（只写本次真正打过分、且拿到了理由的条目）；失败静默降级。 */
    public void putMany(String fingerprint, Map<String, Entry> entries) {
        if (fingerprint == null || fingerprint.isBlank() || entries.isEmpty()) {
            return;
        }
        Duration ttl = Duration.ofSeconds(properties.getDecision().getScoreCacheTtlSeconds());
        int written = 0;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            try {
                redis.opsForValue().set(key(fingerprint, item.getKey()),
                        objectMapper.writeValueAsString(item.getValue()), ttl);
                written++;
            } catch (Exception ex) {
                log.warn("score cache write failed, dishId={}: {}", item.getKey(), ex.getMessage());
                break;
            }
        }
        log.info("score cache wrote {} entries (fingerprint={})", written, fingerprint);
    }

    private String key(String fingerprint, String dishId) {
        return properties.getRedisPrefix() + "score:"
                + properties.getDecision().getRubricVersion() + ":"
                + fingerprint + ":" + dishId;
    }
}
