package com.mineguard.tool.impl;

import com.mineguard.device.IndustrialGateway;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ListDetectionTasksTool implements Tool {
    private final IndustrialGateway gateway;
    public ListDetectionTasksTool(IndustrialGateway gateway) { this.gateway = gateway; }
    @Override public String name() { return "list_detection_tasks"; }
    @Override public String description() { return "List detection algorithms and their current states, optionally for one camera."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of("cameraId", ToolSchema.Field.string()), Set.of(), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        return ToolResult.success(gateway.listDetectionTasks(ToolArguments.string(args, "cameraId")));
    }
}
