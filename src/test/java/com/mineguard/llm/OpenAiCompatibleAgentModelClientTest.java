package com.mineguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.AgentPlanValidator;
import com.mineguard.agent.StructuredPlanner;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenAiCompatibleAgentModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private volatile String response;
    private volatile int status;
    private volatile long delayMs;
    private volatile JsonNode request;
    private volatile String authorization;

    @BeforeEach
    void startLocalServer() throws Exception {
        // 仅使用本机假服务和无效测试凭据，绝不读取用户密钥或访问付费接口。
        status = 200;
        response = completion("{}", "stop", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.incrementAndGet();
            request = mapper.readTree(exchange.getRequestBody());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stopLocalServer() { server.stop(0); }

    private MineGuardProperties.Llm config(int maxCalls, int timeoutSeconds, String thinking) {
        return new MineGuardProperties.Llm("openai-compatible", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "offline-test-secret", "deepseek-v4-flash", maxCalls, 2048, timeoutSeconds, thinking);
    }

    private OpenAiCompatibleAgentModelClient client(int maxCalls) {
        return new OpenAiCompatibleAgentModelClient(config(maxCalls, 3, "disabled"), mapper);
    }

    private String call(OpenAiCompatibleAgentModelClient client) { return client.createPlan("只读测试", List.of(), null); }

    private String completion(String content, String finish, String usage) throws Exception {
        return "{\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":{\"content\":" + mapper.writeValueAsString(content)
                + ",\"reasoning_content\":\"不应记录的内容\"}}],\"usage\":" + usage + "}";
    }

    @Test void sendsDeepSeekContractAndRecordsOnlyUsage() throws Exception {
        response = completion("{\"intent\":\"查询\"}", "stop", """
                {"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
                 "prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6,"completion_tokens_details":{"reasoning_tokens":2}}
                """);
        var client = client(2);
        assertThat(call(client)).isEqualTo("{\"intent\":\"查询\"}");
        assertThat(authorization).isEqualTo("Bearer offline-test-secret");
        assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(2048);
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("stream").asBoolean()).isFalse();
        var usage = client.usageSnapshot();
        assertThat(usage.usageComplete()).isTrue();
        assertThat(usage.recordedTotalTokens()).isEqualTo(15);
        assertThat(usage.recordedPromptCacheHitTokens()).isEqualTo(4);
        assertThat(usage.recordedPromptCacheMissTokens()).isEqualTo(6);
        assertThat(usage.recordedReasoningTokens()).isEqualTo(2);
        assertThat(mapper.writeValueAsString(usage)).doesNotContain("不应记录的内容", "offline-test-secret", "只读测试");
        assertThat(config(2, 3, "").toString()).doesNotContain("offline-test-secret");
        assertThat(client.realModel()).isTrue();
        assertThat(client.providerName()).contains("deepseek-v4-flash");
    }

    @Test void genericProviderDoesNotSendThinkingExtension() {
        call(new OpenAiCompatibleAgentModelClient(config(1, 3, ""), mapper));
        assertThat(request.has("thinking")).isFalse();
    }

    @Test void zeroBudgetSendsNoRequests() {
        var client = client(0);
        assertThatThrownBy(() -> call(client)).hasMessageContaining("额度");
        assertThat(received).hasValue(0);
        assertThat(client.usageSnapshot().attempts()).isZero();
        assertThat(client.usageSnapshot().recordedTotalTokens()).isNull();
    }

    @Test void concurrentRequestsCannotExceedBudget() throws Exception {
        var client = client(3);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                try { call(client); } catch (IllegalStateException ignored) { /* 超额请求应被本地拒绝。 */ }
            })).toList();
            for (var job : jobs) job.get();
        }
        assertThat(received).hasValue(3);
        assertThat(client.usageSnapshot().attempts()).isEqualTo(3);
        assertThat(client.usageSnapshot().rejectedByBudget()).isEqualTo(13);
        assertThat(client.usageSnapshot().recordedTotalTokens()).isEqualTo(45);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"prompt_tokens\":10}",
            "{\"prompt_tokens\":-1,\"completion_tokens\":5,\"total_tokens\":4}",
            "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":99}"})
    void missingOrInvalidUsageIsNotZeroCost(String usage) throws Exception {
        response = completion("{}", "stop", usage);
        var client = client(1);
        call(client);
        assertThat(client.usageSnapshot().usageComplete()).isFalse();
        assertThat(client.usageSnapshot().requestsWithUnknownUsage()).isEqualTo(1);
    }

    @Test void errorsAreRedactedAndNotRetriedOrRefunded() {
        status = 429;
        response = "供应商回显 offline-test-secret";
        var client = client(1);
        assertThatThrownBy(() -> call(client)).hasMessageContaining("429").hasMessageNotContaining("offline-test-secret").hasNoCause();
        assertThatThrownBy(() -> call(client)).hasMessageContaining("额度");
        assertThat(received).hasValue(1);
        assertThat(client.usageSnapshot().failedResponses()).isEqualTo(1);
        assertThat(client.usageSnapshot().requestsWithUnknownUsage()).isEqualTo(1);
    }

    @Test void malformedResponseDoesNotLeakBodyThroughException() {
        response = "{invalid: offline-test-secret}";
        var client = client(1);
        assertThatThrownBy(() -> call(client)).hasMessageContaining("JSON").hasMessageNotContaining("offline-test-secret").hasNoCause();
        assertThat(client.usageSnapshot().failedResponses()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls", "insufficient_system_resource"})
    void truncatedOrRejectedResponsesStillCountTokens(String finish) throws Exception {
        response = completion("{}", finish, "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var client = client(1);
        assertThatThrownBy(() -> call(client)).hasMessageContaining("未正常结束");
        assertThat(client.usageSnapshot().recordedTotalTokens()).isEqualTo(15);
        assertThat(client.usageSnapshot().failedResponses()).isEqualTo(1);
    }

    @Test void timeoutKeepsAttemptAndMarksUnknownUsage() {
        delayMs = 1500;
        var client = new OpenAiCompatibleAgentModelClient(config(1, 1, "disabled"), mapper);
        assertThatThrownBy(() -> call(client)).hasMessageContaining("超时");
        assertThat(client.usageSnapshot().requestsWithUnknownUsage()).isEqualTo(1);
        assertThat(client.usageSnapshot().attempts()).isEqualTo(1);
    }

    @Test void emptyContentIsRejected() throws Exception {
        response = completion("", "stop", "null");
        assertThatThrownBy(() -> call(client(1))).hasMessageContaining("有效计划");
    }

    @Test void plannerRepairUsesSameBudgetAndCountsBothResponses() throws Exception {
        response = completion("{\"intent\":\"查询\",\"riskLevel\":\"LOW\",\"steps\":[]}", "stop",
                "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var client = client(2);
        var validator = mock(AgentPlanValidator.class);
        when(validator.validate(any())).thenReturn(List.of("需要修复"), List.of());
        var registry = mock(ToolRegistry.class);
        when(registry.list()).thenReturn(List.of());
        new StructuredPlanner(client, validator, registry, mapper).plan("测试修复");
        assertThat(received).hasValue(2);
        assertThat(client.usageSnapshot().recordedTotalTokens()).isEqualTo(30);
        assertThat(request.path("messages").path(1).path("content").asText()).contains("需要修复");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://user:password@example.com", "https://example.com?key=x", "not-a-url"})
    void rejectsUnsafeBaseUrls(String url) {
        var config = new MineGuardProperties.Llm("openai-compatible", url, "test", "model", 1, 128, 2, "");
        assertThatThrownBy(() -> new OpenAiCompatibleAgentModelClient(config, mapper)).hasMessageContaining("HTTPS");
        assertThat(received).hasValue(0);
    }
}
