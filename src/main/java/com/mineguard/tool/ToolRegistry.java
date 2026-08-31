package com.mineguard.tool;

import com.mineguard.trace.TraceRecorder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final TraceRecorder traceRecorder;

    public ToolRegistry(List<Tool> discoveredTools, TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
        discoveredTools.forEach(this::register);
    }

    public void register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("duplicate tool: " + tool.name());
    }

    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) throw new NoSuchElementException("unknown tool: " + name);
        return tool;
    }

    public List<Tool> list() {
        return tools.values().stream().sorted(Comparator.comparing(Tool::name)).toList();
    }

    public ToolResult execute(String name, ToolContext context, Map<String, Object> args) {
        long started = System.nanoTime();
        Tool tool;
        try {
            tool = get(name);
        } catch (NoSuchElementException ex) {
            return ToolResult.failure("TOOL_NOT_FOUND", ex.getMessage()).withElapsed(elapsed(started));
        }
        List<String> validation = tool.schema().validate(args);
        if (!validation.isEmpty()) {
            ToolResult result = ToolResult.failure("INVALID_ARGUMENTS", String.join("; ", validation)).withElapsed(elapsed(started));
            trace(context, name, args, result);
            return result;
        }
        if (tool.category() == ToolCategory.HIGH_RISK && !context.approvalGranted()) {
            ToolResult result = ToolResult.failure(APPROVAL_REQUIRED,
                    "high-risk tool execution requires a backend approval grant").withElapsed(elapsed(started));
            trace(context, name, args, result);
            return result;
        }
        try {
            ToolResult result = tool.execute(context, args).withElapsed(elapsed(started));
            trace(context, name, args, result);
            return result;
        } catch (Exception ex) {
            if (ex instanceof com.mineguard.device.IndustrialOutcomeUnknownException) {
                ToolResult result = ToolResult.failure("OUTCOME_UNKNOWN", "工业写请求结果未知，禁止自动重试").withElapsed(elapsed(started));
                trace(context, name, args, result); return result;
            }
            ToolResult result = ToolResult.failure("TOOL_EXECUTION_ERROR", ex.getMessage()).withElapsed(elapsed(started));
            trace(context, name, args, result);
            return result;
        }
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private void trace(ToolContext context, String name, Map<String, Object> args, ToolResult result) {
        traceRecorder.record(context.taskId(), "TOOL_CALL", Map.of(
                "tool", name,
                "args", args == null ? Map.of() : args,
                "success", result.success(),
                "errorCode", result.errorCode() == null ? "" : result.errorCode(),
                "elapsedMs", result.elapsedMs()));
    }
}
