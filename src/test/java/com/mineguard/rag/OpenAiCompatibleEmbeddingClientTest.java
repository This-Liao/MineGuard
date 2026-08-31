package com.mineguard.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class OpenAiCompatibleEmbeddingClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> response = new AtomicReference<>();
    final AtomicReference<JsonNode> sent = new AtomicReference<>();
    HttpServer server;
    java.util.concurrent.ExecutorService executor;
    volatile long delay;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        response.set("{\"model\":\"local-model\",\"data\":[{\"index\":0,\"embedding\":[0.3,0.4]}]}");
        server.createContext("/v1/embeddings", exchange -> {
            try {
                sent.set(mapper.readTree(exchange.getRequestBody()));
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    EmbeddingProperties config(int budget, String prefix, int timeout) {
        return new EmbeddingProperties("openai-compatible", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "private-test-secret", "local-model", 2, timeout, budget, prefix);
    }
    OpenAiCompatibleEmbeddingClient client() { return new OpenAiCompatibleEmbeddingClient(config(8, "检索：", 2), mapper); }

    @Test void separatesQueryPrefixFromDocumentsAndCountsRequests() {
        var client = client();
        assertThat(client.embedQuery("照明不足")).containsExactly(0.3f, 0.4f);
        assertThat(sent.get().path("input").path(0).asText()).isEqualTo("检索：照明不足");
        client.embedDocuments(List.of("处置原文"));
        assertThat(sent.get().path("input").path(0).asText()).isEqualTo("处置原文");
        assertThat(sent.get().path("encoding_format").asText()).isEqualTo("float");
        assertThat(client.requestCount()).isEqualTo(2);
        assertThat(client.dimensions()).isEqualTo(2);
        assertThat(config(8, "", 2).toString()).doesNotContain("private-test-secret");
    }
    @Test void respectsResponseIndicesInsteadOfServerOrder() {
        response.set("{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}");
        var vectors = client().embedDocuments(List.of("甲", "乙"));
        assertThat(vectors.get(0)).containsExactly(1, 0);
        assertThat(vectors.get(1)).containsExactly(0, 1);
    }
    @Test void rejectsMalformedVectorsWithoutSilentHashFallback() {
        for (String json : List.of("{}", "null", "not-json", "{\"data\":[]}",
                "{\"data\":[{\"embedding\":[1,2]}]}",
                "{\"data\":[{\"index\":2,\"embedding\":[1,2]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[\"secret-value\",1]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1e300,1]}]}",
                "{\"model\":\"wrong-model\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}")) {
            response.set(json);
            assertThatThrownBy(() -> client().embed("测试"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-value");
        }
    }
    @Test void rejectsDuplicateBatchIndices() {
        response.set("{\"data\":[{\"index\":0,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}");
        assertThatThrownBy(() -> client().embedDocuments(List.of("甲", "乙"))).hasMessageContaining("索引");
    }
    @Test void errorsConsumeBudgetAndNeverExposeResponseBody() {
        var client = new OpenAiCompatibleEmbeddingClient(config(1, "", 2), mapper);
        status.set(429); response.set("private-test-secret");
        assertThatThrownBy(() -> client.embed("测试")).hasMessageContaining("429").hasMessageNotContaining("private-test-secret");
        assertThatThrownBy(() -> client.embed("再试")).hasMessageContaining("额度");
        assertThat(client.requestCount()).isEqualTo(1);
    }
    @Test void timesOutWithoutRetrying() {
        delay = 2500;
        var client = new OpenAiCompatibleEmbeddingClient(config(1, "", 1), mapper);
        assertThatThrownBy(() -> client.embed("测试")).hasMessageContaining("超时");
        assertThat(client.requestCount()).isEqualTo(1);
    }
    @Test void validatesInputBeforeNetworkAndHandlesEmptyBatch() {
        var client = client();
        assertThat(client.embedDocuments(List.of())).isEmpty();
        assertThatThrownBy(() -> client.embed(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embed("文".repeat(16001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embedDocuments(Arrays.asList("有效", null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.requestCount()).isZero();
    }
    @Test void rejectsUnsafeEndpointsAndInvalidConfiguration() {
        for (String url : List.of("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com?key=secret", "https://example.com/#fragment", "file:///tmp")) {
            assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties(
                    "openai-compatible", url, "secret", "model", 2, 2, 8, ""), mapper)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties(
                "openai-compatible", "https://example.com", "", "model", 2, 2, 8, ""), mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties(
                "openai-compatible", "http://localhost", "secret\n", "model", 2, 2, 8, ""), mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingClient(new EmbeddingProperties(
                "openai-compatible", "http://localhost", "", "", 0, 0, 0, ""), mapper)).isInstanceOf(IllegalArgumentException.class);
    }
}
