package com.mineguard.tool.impl;

import com.mineguard.device.IndustrialGateway;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class GetDeviceStatusTool implements Tool {
    private final IndustrialGateway gateway;
    public GetDeviceStatusTool(IndustrialGateway gateway) { this.gateway = gateway; }
    @Override public String name() { return "get_device_status"; }
    @Override public String description() { return "Return ONLINE, OFFLINE or DEGRADED for a camera/device."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of("deviceId", ToolSchema.Field.string()), Set.of("deviceId"), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String id = ToolArguments.string(args, "deviceId");
        return ToolResult.success(Map.of("deviceId", id, "status", gateway.getDeviceStatus(id)));
    }
}
