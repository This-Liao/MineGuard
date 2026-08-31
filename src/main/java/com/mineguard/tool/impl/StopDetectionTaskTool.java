package com.mineguard.tool.impl;

import com.mineguard.device.IndustrialGateway;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class StopDetectionTaskTool implements Tool {
    private final IndustrialGateway gateway;
    public StopDetectionTaskTool(IndustrialGateway gateway) { this.gateway = gateway; }
    @Override public String name() { return "stop_detection_task"; }
    @Override public String description() { return "Stop an algorithm on a camera. This changes industrial state and always requires approval."; }
    @Override public ToolCategory category() { return ToolCategory.HIGH_RISK; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of("cameraId", ToolSchema.Field.string(), "algorithm", ToolSchema.Field.string()),
                Set.of("cameraId", "algorithm"), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        return ToolResult.success(gateway.stopDetectionTask(
                ToolArguments.string(args, "cameraId"), ToolArguments.string(args, "algorithm"), context.operationKey()));
    }
}
