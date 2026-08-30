package com.mineguard.tool.impl;

import com.mineguard.device.IndustrialGateway;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class VerifyDetectionTaskTool implements Tool {
    private final IndustrialGateway gateway;
    public VerifyDetectionTaskTool(IndustrialGateway gateway) { this.gateway = gateway; }
    @Override public String name() { return "verify_detection_task"; }
    @Override public String description() { return "Independently verify the expected RUNNING or STOPPED state after execution."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of(
                "cameraId", ToolSchema.Field.string(),
                "algorithm", ToolSchema.Field.string(),
                "expectedStatus", ToolSchema.Field.stringEnum("RUNNING", "STOPPED")),
                Set.of("cameraId", "algorithm", "expectedStatus"), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        boolean verified = gateway.verifyDetectionTask(
                ToolArguments.string(args, "cameraId"),
                ToolArguments.string(args, "algorithm"),
                ToolArguments.string(args, "expectedStatus"));
        return verified ? ToolResult.success(Map.of("verified", true, "expectedStatus", ToolArguments.string(args, "expectedStatus")))
                : ToolResult.failure("VERIFICATION_FAILED", "detection task does not match expected state");
    }
}
