package com.mineguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WorkflowConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService workflowExecutor(MineGuardProperties properties) {
        return Executors.newFixedThreadPool(Math.max(1, properties.workflowExecutorThreads()),
                Thread.ofPlatform().name("mineguard-workflow-", 0).factory());
    }
}
