package com.mineguard.tool.impl;

import com.mineguard.event.EventType;
import com.mineguard.event.SafetyEvent;
import com.mineguard.event.SafetyEventRepository;
import com.mineguard.event.Severity;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QuerySafetyEventsTool implements Tool {
    private final SafetyEventRepository repository;

    public QuerySafetyEventsTool(SafetyEventRepository repository) {
        this.repository = repository;
    }

    @Override public String name() { return "query_safety_events"; }
    @Override public String description() { return "Query structured synthetic safety events by area, type, time and severity."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(Map.of(
                "area", ToolSchema.Field.string(),
                "eventType", ToolSchema.Field.stringEnum(Arrays.stream(EventType.values()).map(Enum::name).toArray(String[]::new)),
                "startTime", ToolSchema.Field.string(),
                "endTime", ToolSchema.Field.string(),
                "severity", ToolSchema.Field.stringEnum(Arrays.stream(Severity.values()).map(Enum::name).toArray(String[]::new))),
                Set.of(), false);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        List<SafetyEvent> events = repository.find(ToolArguments.eventFilter(args));
        return ToolResult.success(Map.of("total", events.size(), "events", events.stream().limit(100).toList(),
                "truncated", events.size() > 100));
    }
}
