package com.mineguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.rag.EmbeddingClient;
import com.mineguard.rag.HashingEmbeddingClient;
import com.mineguard.rag.OpenAiCompatibleEmbeddingClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {
    @Bean
    EmbeddingClient embeddingClient(EmbeddingProperties properties, ObjectMapper mapper) {
        return switch (properties.provider()) {
            case "hashing" -> new HashingEmbeddingClient();
            case "openai-compatible" -> new OpenAiCompatibleEmbeddingClient(properties, mapper);
            default -> throw new IllegalArgumentException("未知 Embedding provider，禁止静默回退为哈希向量");
        };
    }
}
