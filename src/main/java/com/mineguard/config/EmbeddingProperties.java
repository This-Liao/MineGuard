package com.mineguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Embedding 与规划模型使用独立配置，避免误传 DeepSeek 密钥。 */
@ConfigurationProperties(prefix = "mineguard.embedding")
public record EmbeddingProperties(String provider, String baseUrl, String apiKey, String model,
                                  int dimensions, int timeoutSeconds, int maxRequests, String queryPrefix) {
    @Override public String toString() {
        return "EmbeddingProperties[provider=" + provider + ", model=" + model + ", apiKey=已隐藏]";
    }
}
