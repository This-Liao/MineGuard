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
        List<Map<String, Object>> schemas = registry.list().stream().map(this::describe).toList();
        String first = model.createPlan(query, schemas, null);
        Attempt attempt = parseAndValidate(first);
        if (attempt.errors().isEmpty()) return attempt.plan();
        String repaired = model.createPlan(query, schemas, "Repair these validation errors: " + String.join("; ", attempt.errors()));
        Attempt retry = parseAndValidate(repaired);
        if (!retry.errors().isEmpty()) throw new PlanningException("invalid plan after repair: " + String.join("; ", retry.errors()));
        return retry.plan();
    }

    public AgentModelClient model() { return model; }

    private Attempt parseAndValidate(String json) {
        try {
            AgentPlan plan = mapper.readValue(json, AgentPlan.class);
            return new Attempt(plan, validator.validate(plan));
        } catch (JsonProcessingException ex) {
            return new Attempt(null, List.of("invalid JSON: " + ex.getOriginalMessage()));
        }
    }

    private Map<String, Object> describe(Tool tool) {
        return Map.of("stepType", AgentStepType.values()[java.util.stream.IntStream.range(0, AgentStepType.values().length)
                        .filter(i -> AgentStepType.values()[i].toolName().equals(tool.name())).findFirst().orElseThrow()].name(),
                "tool", tool.name(), "category", tool.category(), "schema", tool.schema().asJsonSchema());
    }

    private record Attempt(AgentPlan plan, List<String> errors) {}
}
