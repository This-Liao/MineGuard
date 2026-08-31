package com.mineguard.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 严格校验的语义向量适配器：不重试、不打印响应正文、不退回哈希模型。 */
public final class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final int BATCH_SIZE = 32;
    private final EmbeddingProperties config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final AtomicInteger requests = new AtomicInteger();

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        if (config.model() == null || config.model().isBlank() || config.dimensions() < 1
                || config.dimensions() > 16384 || config.timeoutSeconds() < 1 || config.timeoutSeconds() > 300
                || config.maxRequests() < 1) throw new IllegalArgumentException("Embedding 模型、维度、超时或请求上限无效");
        String key = config.apiKey() == null ? "" : config.apiKey();
        if (key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("Embedding 密钥格式无效");
        try {
            URI base = URI.create(config.baseUrl().replaceAll("/+$", ""));
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(base.getHost(), ""));
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !("https".equals(base.getScheme()) || local && "http".equals(base.getScheme()))
                    || !local && key.isBlank()) throw new IllegalArgumentException();
            endpoint = URI.create(base + "/embeddings");
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Embedding 地址须为无内嵌凭据的 HTTPS；仅回环地址允许 HTTP 和空密钥");
        }
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(10, config.timeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public int dimensions() { return config.dimensions(); }
    public int requestCount() { return requests.get(); }
    @Override public float[] embed(String text) { return request(List.of(requireText(text))).getFirst(); }
    @Override public float[] embedQuery(String text) {
        return embed(Objects.toString(config.queryPrefix(), "") + requireText(text));
    }
    @Override public List<float[]> embedDocuments(List<String> texts) {
        Objects.requireNonNull(texts, "文档列表不能为空");
        texts.forEach(OpenAiCompatibleEmbeddingClient::requireText);
        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            vectors.addAll(request(texts.subList(start, Math.min(start + BATCH_SIZE, texts.size()))));
        }
        return List.copyOf(vectors);
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank() || text.length() > 16000) throw new IllegalArgumentException("Embedding 文本必须为 1 至 16000 字符");
        return text;
    }

    private List<float[]> request(List<String> texts) {
        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "model", config.model(), "input", texts, "encoding_format", "float"))));
            if (config.apiKey() != null && !config.apiKey().isBlank()) builder.header("Authorization", "Bearer " + config.apiKey());
            request = builder.build();
        } catch (IOException ex) { throw new IllegalStateException("Embedding 请求序列化失败"); }
        // CAS 在发送前保留额度，失败请求同样计数；并发请求不能突破保护上限。
        int current;
        do {
            current = requests.get();
            if (current >= config.maxRequests()) throw new IllegalStateException("Embedding 请求额度已用尽");
        } while (!requests.compareAndSet(current, current + 1));
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Embedding 返回 HTTP " + response.statusCode() + "，未自动重试");
            if (response.body().length() > 8_000_000) throw new IllegalStateException("Embedding 响应超出大小限制");
            return decode(mapper.readTree(response.body()), texts.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Embedding 连接失败、超时或 JSON 无效；未回退哈希模型");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 请求被中断");
        }
    }

    private List<float[]> decode(JsonNode root, int count) {
        if (root == null || !root.path("data").isArray() || root.path("data").size() != count) {
            throw new IllegalStateException("Embedding 返回数量不匹配");
        }
        if (root.has("model") && !config.model().equals(root.path("model").asText())) {
            throw new IllegalStateException("Embedding 返回模型与配置不一致");
        }
        float[][] ordered = new float[count][];
        for (JsonNode row : root.path("data")) {
            if (!row.path("index").isIntegralNumber()) throw new IllegalStateException("Embedding 索引缺失");
            int index = row.path("index").asInt(-1);
            JsonNode values = row.path("embedding");
            if (index < 0 || index >= count || ordered[index] != null || !values.isArray() || values.size() != dimensions()) {
                throw new IllegalStateException("Embedding 索引、数量或维度无效");
            }
            float[] vector = new float[dimensions()];
            double norm = 0;
            for (int i = 0; i < dimensions(); i++) {
                if (!values.get(i).isNumber()) throw new IllegalStateException("Embedding 含非数值元素");
                vector[i] = values.get(i).floatValue();
                if (!Float.isFinite(vector[i])) throw new IllegalStateException("Embedding 含非有限数值");
                norm += (double) vector[i] * vector[i];
            }
            if (norm == 0) throw new IllegalStateException("Embedding 返回零向量");
            ordered[index] = vector;
        }
        return List.of(ordered);
    }
}
