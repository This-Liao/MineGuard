package com.mineguard.agent;

import java.util.List;

public record AgentPlan(String intent, RiskLevel riskLevel, List<PlanStep> steps) {
    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
