package com.mineguard.tool;

import java.time.Instant;

public record ToolContext(String taskId, boolean approvalGranted, Instant requestedAt) {
    public static ToolContext forTask(String taskId) {
        return new ToolContext(taskId, false, Instant.now());
    }
}
