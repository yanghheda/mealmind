package com.mealmind.biz.decision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.biz.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 运行时的内部调用客户端（服务间令牌鉴权）。
 * 只有业务服务能调 Agent（依赖方向单向），SSE 由业务服务转发给 H5。
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public AgentClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 建立一次决策会话并拿到 SSE 流；返回的流由调用方关闭。 */
    public InputStream decide(String runId, String text, String mode, String merchantId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("text", text);
        payload.put("mode", mode);
        payload.put("merchantId", merchantId);
        HttpRequest request = base("/internal/decide")
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new AgentUnavailableException("agent decide returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new AgentUnavailableException("agent decide failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("agent decide interrupted", ex);
        }
    }

    /** 逐行解析 SSE（event: / data:，空行分帧），交给回调处理。 */
    public void readEvents(InputStream body, Consumer<AgentEvent> consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null && data != null) {
                        consumer.accept(new AgentEvent(event, parseData(data.toString())));
                    }
                    event = null;
                    data = null;
                } else if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data == null) {
                        data = new StringBuilder();
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        } catch (IOException ex) {
            // 超时看门狗关闭连接或 Agent 中断：交给调用方兜底
            log.warn("agent stream read aborted: {}", ex.getMessage());
            throw ex;
        }
    }

    /** 协作式编排的续接调用：把约束过滤后的候选回填给暂停中的图。 */
    public void provideCandidates(String runId, List<AgentCandidate> candidates) {
        HttpRequest request = base("/internal/decide/" + runId + "/candidates")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(Map.of("candidates", candidates)), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AgentUnavailableException("provide candidates returned " + response.statusCode());
            }
        } catch (IOException ex) {
            throw new AgentUnavailableException("provide candidates failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("provide candidates interrupted", ex);
        }
    }

    private HttpRequest.Builder base(String path) {
        String baseUrl = properties.getAgentBaseUrl();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimmed + path));
        String token = properties.getInternalToken();
        if (token != null && !token.isBlank()) {
            builder.header(TOKEN_HEADER, token);
        }
        return builder;
    }

    private JsonNode parseData(String json) {
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
            throw new AgentUnavailableException("serialize payload failed", ex);
        }
    }

    /** Agent 事件（event 名 + data JSON）。 */
    public record AgentEvent(String name, JsonNode data) {
    }

    /** Agent 不可用 / 中断 / 协议异常。 */
    public static class AgentUnavailableException extends RuntimeException {

        public AgentUnavailableException(String message) {
            super(message);
        }

        public AgentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}