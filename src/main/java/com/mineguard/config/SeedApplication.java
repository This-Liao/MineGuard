package com.mineguard.config;

import com.mineguard.MineGuardApplication;
import com.mineguard.event.SafetyEventRepository;
import com.mineguard.rag.KnowledgeRetriever;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public final class SeedApplication {
    private SeedApplication() {}
    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(MineGuardApplication.class).web(WebApplicationType.NONE).run(args)) {
            long events = context.getBean(SafetyEventRepository.class).count();
            int chunks = context.getBean(KnowledgeRetriever.class).indexedChunkCount();
            System.out.printf("Seed complete: %d synthetic events, %d knowledge chunks.%n", events, chunks);
        }
    }
}
