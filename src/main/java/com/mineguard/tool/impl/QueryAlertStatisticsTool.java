package com.mineguard.tool.impl;

import com.mineguard.event.EventType;
import com.mineguard.event.SafetyEventFilter;
import com.mineguard.event.SafetyEventRepository;
import com.mineguard.event.Severity;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QueryAlertStatisticsTool implements Tool {
    private final SafetyEventRepository repository;
    public QueryAlertStatisticsTool(SafetyEventRepository repository) { this.repository = repository; }
    @Override public String name() { return "query_alert_statistics"; }
    @Override public String description() { return "Aggregate safety alerts by event type after structured filters."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of(
                "area", ToolSchema.Field.string(),
                "eventType", ToolSchema.Field.stringEnum(Arrays.stream(EventType.values()).map(Enum::name).toArray(String[]::new)),
                "startTime", ToolSchema.Field.string(),
                "endTime", ToolSchema.Field.string(),
                "severity", ToolSchema.Field.stringEnum(Arrays.stream(Severity.values()).map(Enum::name).toArray(String[]::new))),
                Set.of(), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        SafetyEventFilter filter = ToolArguments.eventFilter(args);
        Map<String, Long> counts = repository.aggregate(filter);
        return ToolResult.success(Map.of("filters", filter, "total", counts.values().stream().mapToLong(Long::longValue).sum(), "byType", counts));
    }
}
