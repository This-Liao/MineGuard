package com.mineguard.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus REST v2 适配器，要求集合包含 id、documentId、title、chunkId、content 和 vector 字段。
 * 在本地保留元数据，将检索返回的实体标识映射为文档块，避免领域对象耦合 Milvus 协议。
 */
public class MilvusVectorStore implements VectorStore {
    public static final String COLLECTION = "mineguard_knowledge";
    private final URI baseUri;
    private final ObjectMapper mapper;
    private final String collection;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, DocumentChunk> chunks = new ConcurrentHashMap<>();

    public MilvusVectorStore(String uri, ObjectMapper mapper) {
        this(uri, mapper, COLLECTION);
    }

    public MilvusVectorStore(String uri, ObjectMapper mapper, String collection) {
        if (collection == null || !collection.matches("[A-Za-z_][A-Za-z0-9_]{0,254}")) {
            throw new IllegalArgumentException("Milvus 集合名称无效");
        }
        this.baseUri = URI.create(uri.replaceAll("/$", ""));
        this.mapper = mapper;
        this.collection = collection;
    }

    @Override
    public synchronized void replaceAll(List<VectorEntry> entries) {
        Map<String, DocumentChunk> next = new LinkedHashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        for (VectorEntry entry : entries) {
            DocumentChunk chunk = entry.chunk();
            if (chunk.chunkId() == null || chunk.chunkId().isBlank() || next.putIfAbsent(chunk.chunkId(), chunk) != null) {
                throw new IllegalArgumentException("文档块 ID 必须非空且唯一");
            }
            data.add(Map.of(
                    "id", chunk.chunkId(),
                    "documentId", chunk.documentId(),
                    "title", chunk.title(),
                    "chunkId", chunk.chunkId(),
                    "content", chunk.content(),
                    "vector", entry.vector()));
        }
        // 集合必须由本知识库独占。先写入新值，再删除不在本次快照中的旧块；这不是跨请求事务。
        if (!data.isEmpty()) post("/v2/vectordb/entities/upsert", Map.of("collectionName", collection, "data", data));
        try {
            String filter = next.isEmpty() ? "id >= \"\"" : "id not in " + mapper.writeValueAsString(next.keySet());
            post("/v2/vectordb/entities/delete", Map.of("collectionName", collection, "filter", filter));
        } catch (IOException ex) {
            throw new IllegalStateException("无法编码文档块标识");
        }
        chunks.clear();
        chunks.putAll(next);
    }

    @Override
    public List<VectorMatch> search(float[] queryVector, int topK) {
        JsonNode response = post("/v2/vectordb/entities/search", Map.of(
                "collectionName", collection,
                "consistencyLevel", "Strong",
                "data", List.of(queryVector),
                "annsField", "vector",
                "limit", topK,
                "outputFields", List.of("documentId", "title", "chunkId", "content")));
        List<VectorMatch> matches = new ArrayList<>();
        JsonNode data = response.path("data");
        if (data.isArray()) for (JsonNode item : data) {
            String chunkId = item.path("chunkId").asText(item.path("id").asText());
            DocumentChunk chunk = chunks.computeIfAbsent(chunkId, ignored -> new DocumentChunk(
                    item.path("documentId").asText(), item.path("title").asText(), chunkId, item.path("content").asText()));
            matches.add(new VectorMatch(chunk, item.path("distance").asDouble(item.path("score").asDouble())));
        }
        return matches;
    }

    @Override
    public int size() {
        return chunks.size();
    }

    private JsonNode post(String path, Object payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Milvus HTTP " + response.statusCode());
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.path("code").isIntegralNumber() || json.path("code").asInt() != 0) {
                throw new IllegalStateException("Milvus 未返回成功状态");
            }
            return json;
        } catch (IOException ex) {
            throw new IllegalStateException("Milvus request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Milvus request interrupted", ex);
        }
    }
}
