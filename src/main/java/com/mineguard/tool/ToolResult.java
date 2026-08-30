package com.mineguard.tool;

public record ToolResult(boolean success, Object data, String errorCode, String errorMessage, long elapsedMs) {
    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null, null, 0);
    }

    public static ToolResult failure(String code, String message) {
        return new ToolResult(false, null, code, message, 0);
    }

    public ToolResult withElapsed(long elapsed) {
        return new ToolResult(success, data, errorCode, errorMessage, elapsed);
    }
}
