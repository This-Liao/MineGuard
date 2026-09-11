package com.mineguard.config;

import com.mineguard.rag.LuceneBm25Index;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {
    @Bean(destroyMethod = "close")
    LuceneBm25Index luceneBm25Index() {
        return new LuceneBm25Index();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService retrievalExecutor() {
        return Executors.newFixedThreadPool(2, Thread.ofPlatform().name("hybrid-retrieval-", 0).factory());
    }
}
