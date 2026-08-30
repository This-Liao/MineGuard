package com.mineguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mineguard")
public record MineGuardProperties(
        Llm llm,
        VectorStore vectorStore,
        String knowledgePath,
        String tracePath,
        int workflowExecutorThreads
) {
    public record Llm(String provider, String baseUrl, String apiKey, String model) {}
    public record VectorStore(String type, String milvusUri) {}
}
