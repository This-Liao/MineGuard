package com.mineguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RuntimeProperties.class)
public class WorkflowConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService workflowExecutor(MineGuardProperties properties) {
        return Executors.newFixedThreadPool(Math.max(1, properties.workflowExecutorThreads()),
                Thread.ofPlatform().name("mineguard-workflow-", 0).factory());
    }
}
