package com.mealmind.biz.decision;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单个 run 的事件流：统一分配 seq、落 Redis 进度流（断线按 seq 补发）、
 * 并实时分发给当前订阅者。事件按到达顺序串行写入，订阅与回放在同一把锁内完成，避免丢帧。
 */
public class RunStream {

    private static final Logger log = LoggerFactory.getLogger(RunStream.class);
    private static final long EMITTER_TIMEOUT_MS = 300_000L;
    private static final Duration BACKLOG_TTL = Duration.ofMinutes(30);

    private final String runId;
    private final String redisKey;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private final Set<SseEmitter> subscribers = new CopyOnWriteArraySet<>();
    private volatile boolean terminal = false;

    RunStream(String runId, String redisKey, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.runId = runId;
        this.redisKey = redisKey;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public synchronized void emit(String event, Object data) {
        int currentSeq = seq.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", currentSeq);
        payload.put("event", event);
        payload.put("data", data);
        history.add(payload);
        persist(payload);
        for (SseEmitter emitter : subscribers) {
            send(emitter, currentSeq, event, data);
        }
        if ("done".equals(event) || "error".equals(event)) {
            terminal = true;
        }
    }

    /** 订阅：先按 lastSeq 回放缺口，再挂到实时分发的订阅者集合；已结束的 run 回放后直接关闭。 */
    public synchronized SseEmitter subscribe(long lastSeq) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        for (Map<String, Object> payload : backlog()) {
            int itemSeq = ((Number) payload.get("seq")).intValue();
            if (itemSeq > lastSeq) {
                send(emitter, itemSeq, String.valueOf(payload.get("event")), payload.get("data"));
            }
        }
        if (terminal) {
            emitter.complete();
        } else {
            subscribers.add(emitter);
        }
        return emitter;
    }

    public void heartbeat() {
        if (terminal) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Map.of("runId", runId), MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                subscribers.remove(emitter);
            }
        }
    }

    public synchronized void finish() {
        terminal = true;
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 订阅端已断开，忽略
            }
        }
        subscribers.clear();
    }

    public boolean isTerminal() {
        return terminal;
    }

    public int lastSeq() {
        return seq.get();
    }

    public int historySize() {
        return history.size();
    }

    /** 回放来源：优先 Redis 进度流（跨实例/重启口径），Redis 不可用时退回进程内历史。 */
    private List<Map<String, Object>> backlog() {
        try {
            List<String> raw = redis.opsForList().range(redisKey, 0, -1);
            if (raw != null && !raw.isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>(raw.size());
                for (String json : raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = objectMapper.readValue(json, Map.class);
                    items.add(item);
                }
                return items;
            }
        } catch (Exception ex) {
            log.warn("redis backlog read failed, fallback to memory. runId={}", runId, ex);
        }
        return List.copyOf(history);
    }

    private void persist(Map<String, Object> payload) {
        try {
            redis.opsForList().rightPush(redisKey, objectMapper.writeValueAsString(payload));
            redis.expire(redisKey, BACKLOG_TTL);
        } catch (Exception ex) {
            log.warn("redis backlog write failed, ignored. runId={}", runId, ex);
        }
    }

    private void send(SseEmitter emitter, int currentSeq, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(currentSeq))
                    .name(event)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            subscribers.remove(emitter);
        }
    }
}