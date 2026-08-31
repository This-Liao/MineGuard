package com.mineguard.agent;

import com.mineguard.tool.Tool;
import com.mineguard.tool.ToolCategory;
import com.mineguard.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AgentPlanValidator {
    private final ToolRegistry toolRegistry;

    public AgentPlanValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public List<String> validate(AgentPlan plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) return List.of("plan is null");
        if (plan.intent() == null || plan.intent().isBlank()) errors.add("intent is required");
        if (plan.riskLevel() == null) errors.add("riskLevel is required");
        if (plan.steps().isEmpty() || plan.steps().size() > 10) errors.add("steps must contain 1-10 entries");
        Set<String> ids = new HashSet<>();
        Set<String> targets = new HashSet<>();
        boolean highRisk = false;
        for (PlanStep step : plan.steps()) {
            if (step.id() == null || !step.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(step.id())) errors.add("step id must be non-empty and unique, using 1-64 safe characters");
            if (step.type() == null) { errors.add("step type is required"); continue; }
            try {
                Tool tool = toolRegistry.get(step.type().toolName());
                List<String> argumentErrors = tool.schema().validate(step.args());
                argumentErrors.forEach(error -> errors.add(step.id() + ": " + error));
                highRisk |= tool.category() == ToolCategory.HIGH_RISK;
                if (tool.category() == ToolCategory.HIGH_RISK && !targets.add(step.args().get("cameraId") + "::" + step.args().get("algorithm"))) {
                    errors.add("同一检测目标在一个计划中只能包含一次写操作");
                }
            } catch (Exception ex) {
                errors.add(step.id() + ": " + ex.getMessage());
            }
        }
        if (highRisk && plan.riskLevel() != RiskLevel.HIGH) errors.add("high-risk tools require plan riskLevel HIGH");
        return errors;
    }
}
