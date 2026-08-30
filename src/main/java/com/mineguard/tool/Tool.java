package com.mineguard.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    ToolCategory category();
    ToolSchema schema();
    ToolResult execute(ToolContext context, Map<String, Object> args);
}
