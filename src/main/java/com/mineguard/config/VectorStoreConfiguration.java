package com.mineguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.rag.InMemoryVectorStore;
import com.mineguard.rag.MilvusVectorStore;
import com.mineguard.rag.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfiguration {
    @Bean
    VectorStore vectorStore(MineGuardProperties properties, ObjectMapper objectMapper) {
        if ("milvus".equalsIgnoreCase(properties.vectorStore().type())) {
            return new MilvusVectorStore(properties.vectorStore().milvusUri(), objectMapper);
        }
        return new InMemoryVectorStore();
    }
}
