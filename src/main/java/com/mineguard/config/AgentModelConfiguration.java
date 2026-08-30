package com.mineguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.llm.DeterministicAgentModelClient;
import com.mineguard.llm.OpenAiCompatibleAgentModelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentModelConfiguration {
    @Bean
    AgentModelClient agentModelClient(MineGuardProperties properties, ObjectMapper mapper) {
        if ("openai-compatible".equalsIgnoreCase(properties.llm().provider())) {
            return new OpenAiCompatibleAgentModelClient(properties.llm(), mapper);
        }
        return new DeterministicAgentModelClient(mapper);
    }
}
