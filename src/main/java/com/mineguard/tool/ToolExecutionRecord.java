package com.mineguard.tool;

import java.time.Instant;
import java.util.Map;

public record ToolExecutionRecord(String toolName, Map<String, Object> args, ToolCategory category,
                                  ToolResult result, Instant startedAt) {}
