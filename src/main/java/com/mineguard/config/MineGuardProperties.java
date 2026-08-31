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
    public record Llm(String provider, String baseUrl, String apiKey, String model,
                      int maxCalls, int maxOutputTokens, int requestTimeoutSeconds, String thinking) {
        @Override public String toString() {
            return "Llm[provider=" + provider + ", model=" + model + ", apiKey=已隐藏]";
        }
    }
    public record VectorStore(String type, String milvusUri) {}
}
