package com.mineguard.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleAgentModelClient implements AgentModelClient {
    private final MineGuardProperties.Llm config;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public OpenAiCompatibleAgentModelClient(MineGuardProperties.Llm config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is required when provider=openai-compatible");
        }
    }

    @Override
    public String createPlan(String userQuery, List<Map<String, Object>> availableTools, String correction) {
        String system = """
                You plan MineGuard industrial safety tasks. Return JSON only with intent, riskLevel (LOW/MEDIUM/HIGH),
                and 1-10 steps. Each step has id, type, description and args. Use only the provided step types.
                Never claim approval. START_DETECTION_TASK and STOP_DETECTION_TASK are HIGH risk.
                Do not include reasoning or hidden chain of thought.
                """;
        try {
            String user = mapper.writeValueAsString(Map.of(
                    "query", userQuery,
                    "availableTools", availableTools,
                    "correction", correction == null ? "" : correction));
            Map<String, Object> body = Map.of(
                    "model", config.model(),
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            URI uri = URI.create(config.baseUrl().replaceAll("/$", "") + "/chat/completions");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw new IllegalStateException("model HTTP " + response.statusCode() + ": " + response.body());
            JsonNode json = mapper.readTree(response.body());
            return json.path("choices").path(0).path("message").path("content").asText();
        } catch (IOException ex) {
            throw new IllegalStateException("model request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model request interrupted", ex);
        }
    }

    @Override public String providerName() { return "openai-compatible:" + config.model(); }
    @Override public boolean realModel() { return true; }
}
