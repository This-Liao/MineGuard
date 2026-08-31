package com.mineguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.agent.PlanningContract;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OpenAiCompatibleAgentModelClient implements AgentModelClient {
    private final MineGuardProperties.Llm config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final ModelUsageRecorder usage;

    public OpenAiCompatibleAgentModelClient(MineGuardProperties.Llm config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        if (config.apiKey() == null || config.apiKey().isBlank() || config.apiKey().contains("\n") || config.apiKey().contains("\r")) {
            throw new IllegalStateException("真实模型需要配置有效的 OPENAI_API_KEY");
        }
        if (config.model() == null || config.model().isBlank() || config.maxOutputTokens() < 1 || config.requestTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("模型 ID、输出 Token 上限和请求超时必须有效");
        }
        if (config.thinking() != null && !config.thinking().isBlank()
                && !Set.of("enabled", "disabled").contains(config.thinking())) {
            throw new IllegalArgumentException("thinking 只能留空或设置为 enabled/disabled");
        }
        try {
            URI base = URI.create(config.baseUrl().replaceAll("/+$", ""));
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !("https".equals(base.getScheme()) || (loopback && "http".equals(base.getScheme())))) {
                throw new IllegalArgumentException();
            }
            this.endpoint = URI.create(base + "/chat/completions");
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("模型基础地址须为不含凭据的 HTTPS URL；仅本机离线测试允许 HTTP");
        }
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(10, config.requestTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.usage = new ModelUsageRecorder(config.maxCalls());
    }

    @Override
    public String createPlan(String userQuery, List<Map<String, Object>> availableTools, String correction) {
        String system = PlanningContract.SYSTEM_PROMPT;
        HttpRequest request;
        try {
            String user = mapper.writeValueAsString(Map.of(
                    "query", userQuery,
                    "referenceTimeUtc", Instant.now().toString(),
                    "availableTools", availableTools,
                    "correction", correction == null ? "" : correction));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("temperature", 0);
            body.put("max_tokens", config.maxOutputTokens());
            body.put("stream", false);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            if (config.thinking() != null && !config.thinking().isBlank()) body.put("thinking", Map.of("type", config.thinking()));
            request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("模型请求序列化失败");
        }
        int number = usage.reserve();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        int status = 0;
        String outcome = "INVALID_RESPONSE";
        ModelUsageRecorder.Tokens tokens = ModelUsageRecorder.Tokens.unknown();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            if (status != 200) {
                outcome = "HTTP_ERROR";
                // 不回显服务端正文，避免错误页中的凭据或用户输入进入 Trace。
                throw new IllegalStateException("模型请求返回 HTTP " + status + "，未自动重试");
            }
            JsonNode json = mapper.readTree(response.body());
            if (json == null) throw new IllegalStateException("模型返回空响应");
            tokens = ModelUsageRecorder.Tokens.from(json.path("usage"));
            JsonNode choice = json.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) {
                outcome = "INCOMPLETE_RESPONSE";
                throw new IllegalStateException("模型回复未正常结束，请检查输出 Token 上限或服务状态");
            }
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) throw new IllegalStateException("模型未返回有效计划文本");
            outcome = "SUCCESS";
            return content.asText();
        } catch (IOException ex) {
            outcome = status == 200 ? "INVALID_RESPONSE" : "IO_ERROR";
            // 不挂接可能含响应片段的解析异常；超时也不代表服务端没有计费。
            throw new IllegalStateException(status == 200 ? "模型响应不是有效 JSON" : "模型连接失败或请求超时，用量未知");
        } catch (InterruptedException ex) {
            outcome = "INTERRUPTED";
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型请求被中断，用量未知");
        } finally {
            usage.complete(number, startedAt, Math.max(0, (System.nanoTime() - start) / 1_000_000), status, outcome, tokens);
        }
    }

    public ModelUsageRecorder.Snapshot usageSnapshot() { return usage.snapshot(); }
    @Override public String providerName() { return "openai-compatible:" + config.model(); }
    @Override public boolean realModel() { return true; }
}
