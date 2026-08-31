package com.mineguard.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.tool.Tool;
import com.mineguard.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StructuredPlanner {
    private final AgentModelClient model;
    private final AgentPlanValidator validator;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public StructuredPlanner(AgentModelClient model, AgentPlanValidator validator, ToolRegistry registry, ObjectMapper mapper) {
        this.model = model;
        this.validator = validator;
        this.registry = registry;
        this.mapper = mapper;
    }

    public AgentPlan plan(String query) {
        List<Map<String, Object>> schemas = registry.list().stream()
                .filter(tool -> !tool.name().equals(AgentStepType.VERIFY_DETECTION_TASK.toolName())).map(this::describe).toList();
        String first = model.createPlan(query, schemas, null);
        Attempt attempt = parseAndValidate(query, first);
        if (attempt.errors().isEmpty()) return attempt.plan();
        String feedback;
        try {
            feedback = mapper.writeValueAsString(Map.of("errors", attempt.errors(),
                    "previousPlan", first == null ? "" : first.substring(0, Math.min(first.length(), 12000))));
        } catch (JsonProcessingException ex) { throw new PlanningException("无法序列化规划反馈"); }
        String repaired = model.createPlan(query, schemas, feedback);
        Attempt retry = parseAndValidate(query, repaired);
        if (!retry.errors().isEmpty()) throw new PlanningException("invalid plan after repair: " + String.join("; ", retry.errors()));
        return retry.plan();
    }

    public AgentModelClient model() { return model; }

    private Attempt parseAndValidate(String query, String json) {
        try {
            if (json == null || json.isBlank()) return new Attempt(null, List.of("模型计划为空"));
            AgentPlan plan = mapper.readValue(json, AgentPlan.class);
            List<String> errors = validator.validate(plan);
            return new Attempt(plan, errors.isEmpty() ? PlanningContract.validate(query, plan) : errors);
        } catch (JsonProcessingException ex) {
            return new Attempt(null, List.of("invalid JSON: " + ex.getOriginalMessage()));
        }
    }

    private Map<String, Object> describe(Tool tool) {
        return Map.of("stepType", AgentStepType.values()[java.util.stream.IntStream.range(0, AgentStepType.values().length)
                        .filter(i -> AgentStepType.values()[i].toolName().equals(tool.name())).findFirst().orElseThrow()].name(),
                "tool", tool.name(), "description", tool.description(), "category", tool.category(), "schema", tool.schema().asJsonSchema());
    }

    private record Attempt(AgentPlan plan, List<String> errors) {}
}
