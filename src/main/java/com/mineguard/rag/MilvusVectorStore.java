package com.mineguard.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus REST v2 adapter. The collection must contain id, documentId, title, chunkId, content and vector fields.
 * Payload metadata is retained locally so returned entity ids can be mapped without coupling domain objects to Milvus.
 */
public class MilvusVectorStore implements VectorStore {
    public static final String COLLECTION = "mineguard_knowledge";
    private final URI baseUri;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, DocumentChunk> chunks = new ConcurrentHashMap<>();

    public MilvusVectorStore(String uri, ObjectMapper mapper) {
        this.baseUri = URI.create(uri.replaceAll("/$", ""));
        this.mapper = mapper;
    }

    @Override
    public void replaceAll(List<VectorEntry> entries) {
        chunks.clear();
        List<Map<String, Object>> data = new ArrayList<>();
        for (VectorEntry entry : entries) {
            DocumentChunk chunk = entry.chunk();
            chunks.put(chunk.chunkId(), chunk);
            data.add(Map.of(
                    "id", chunk.chunkId(),
                    "documentId", chunk.documentId(),
                    "title", chunk.title(),
                    "chunkId", chunk.chunkId(),
                    "content", chunk.content(),
                    "vector", entry.vector()));
        }
        post("/v2/vectordb/entities/insert", Map.of("collectionName", COLLECTION, "data", data));
    }

    @Override
    public List<VectorMatch> search(float[] queryVector, int topK) {
        JsonNode response = post("/v2/vectordb/entities/search", Map.of(
                "collectionName", COLLECTION,
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
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw new IllegalStateException("Milvus HTTP " + response.statusCode() + ": " + response.body());
            JsonNode json = mapper.readTree(response.body());
            if (json.path("code").asInt(0) != 0) throw new IllegalStateException("Milvus error: " + response.body());
            return json;
        } catch (IOException ex) {
            throw new IllegalStateException("Milvus request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Milvus request interrupted", ex);
        }
    }
}
