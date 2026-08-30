package com.mineguard.agent;

import java.util.Map;

public record PlanStep(String id, AgentStepType type, String description, Map<String, Object> args) {
    public PlanStep {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
