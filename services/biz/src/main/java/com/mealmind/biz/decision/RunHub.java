package com.mealmind.biz.decision;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.config.AppProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 在途 run 的事件流仓库：心跳与过期清理由一个轻量调度器驱动。 */
@Component
public class RunHub {

    /** 终态 run 的事件流保留时长（毫秒）：供断线订阅者回放。 */
    private static final long RETENTION_MS = 10 * 60 * 1000L;

    private final Map<String, RunStream> streams = new ConcurrentHashMap<>();
    private final Map<String, Long> terminalAt = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public RunHub(StringRedisTemplate redis, ObjectMapper objectMapper, AppProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        int heartbeatSeconds = properties.getDecision().getHeartbeatSeconds();
        scheduler.scheduleAtFixedRate(this::heartbeatAll, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    public RunStream create(String runId) {
        RunStream stream = new RunStream(
                runId,
                properties.getRedisPrefix() + "run:" + runId + ":events",
                redis,
                objectMapper);
        streams.put(runId, stream);
        return stream;
    }

    public RunStream get(String runId) {
        return streams.get(runId);
    }

    private void heartbeatAll() {
        streams.forEach((runId, stream) -> {
            if (!stream.isTerminal()) {
                stream.heartbeat();
            } else {
                terminalAt.putIfAbsent(runId, System.currentTimeMillis());
            }
        });
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        terminalAt.forEach((runId, at) -> {
            if (now - at > RETENTION_MS) {
                streams.remove(runId);
                terminalAt.remove(runId);
            }
        });
    }
}