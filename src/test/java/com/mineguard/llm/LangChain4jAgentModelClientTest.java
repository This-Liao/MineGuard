package com.mineguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jAgentModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void aiServices应发起真实OpenAI兼容请求并解析类型化计划() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            String plan = """
                    {"intent":"查询安全帽规范","riskLevel":"LOW","steps":[
                      {"id":"s1","type":"SEARCH_SAFETY_KNOWLEDGE","description":"检索规范",
                       "args":{"area":null,"eventType":null,"startTime":null,"endTime":null,
                       "severity":null,"query":"安全帽规范","topK":5,"cameraId":null,"deviceId":null,
                       "algorithm":null,"expectedStatus":null,"riskTopic":null}}
                    ]}
                    """;
            respond(exchange, 200, openAiResponse(plan));
        });
        server.start();

        var client = new LangChain4jAgentModelClient(config(2), mapper);
        JsonNode plan = mapper.readTree(client.createPlan("查询安全帽规范", List.of(
                Map.of("name", "search_safety_knowledge")), null));

        assertThat(plan.path("intent").asText()).isEqualTo("查询安全帽规范");
        assertThat(plan.path("steps").path(0).path("args").path("query").asText()).isEqualTo("安全帽规范");
        assertThat(request.get().path("model").asText()).isEqualTo("deepseek-test");
        assertThat(request.get().path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.get().path("max_tokens").asInt()).isEqualTo(512);
        assertThat(request.get().path("messages").toString()).contains("availableTools", "安全帽");
        assertThat(client.usageSnapshot().recordedTotalTokens()).isEqualTo(15L);
        assertThat(client.providerName()).isEqualTo("langchain4j-openai-compatible:deepseek-test");
    }

    @Test
    void 禁用自动重试且失败请求也消耗调用额度() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "{\"error\":{\"message\":\"不要回显的正文\"}}");
        });
        server.start();
        var client = new LangChain4jAgentModelClient(config(1), mapper);

        assertThatThrownBy(() -> client.createPlan("查询", List.of(), null))
                .hasMessage("模型请求返回 HTTP 500，未自动重试")
                .hasMessageNotContaining("不要回显");
        assertThatThrownBy(() -> client.createPlan("再次查询", List.of(), null))
                .hasMessageContaining("调用额度已用完");
        assertThat(requests).hasValue(1);
    }

    @Test
    void 公网HTTP地址应被拒绝() {
        var invalid = new MineGuardProperties.Llm("langchain4j", "http://example.com", "secret",
                "deepseek-test", 1, 512, 3, "");
        assertThatThrownBy(() -> new LangChain4jAgentModelClient(invalid, mapper))
                .hasMessageContaining("HTTPS");
    }

    private MineGuardProperties.Llm config(int maxCalls) {
        return new MineGuardProperties.Llm("langchain4j-openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", "deepseek-test",
                maxCalls, 512, 3, "disabled");
    }

    private String openAiResponse(String content) throws IOException {
        return mapper.writeValueAsString(Map.of(
                "id", "chatcmpl-test",
                "object", "chat.completion",
                "created", 1,
                "model", "deepseek-test",
                "choices", List.of(Map.of(
                        "index", 0,
                        "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
