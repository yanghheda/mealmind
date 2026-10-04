package com.mealmind.biz.service;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis cache-aside 封装：键统一带 mealmind: 前缀。
 * 缓存读写一律吞掉异常并降级为「未命中 / 不写」，绝不影响请求正确性。
 */
@Service
public class JsonCacheService {

    private static final Logger log = LoggerFactory.getLogger(JsonCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public JsonCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String key(String suffix) {
        return properties.getRedisPrefix() + suffix;
    }

    public <T> T get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("redis get failed, fall through. key={}", key, ex);
            return null;
        }
    }

    public void put(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(properties.getCacheTtlSeconds()));
        } catch (Exception ex) {
            log.warn("redis set failed, ignored. key={}", key, ex);
        }
    }
}
