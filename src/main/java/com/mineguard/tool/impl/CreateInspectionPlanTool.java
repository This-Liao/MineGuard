package com.mineguard.tool.impl;

import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CreateInspectionPlanTool implements Tool {
    @Override public String name() { return "create_inspection_plan"; }
    @Override public String description() { return "Create a synthetic, reviewable inspection work plan from an area and risk topic."; }
    @Override public ToolCategory category() { return ToolCategory.WRITE; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of("area", ToolSchema.Field.string(), "riskTopic", ToolSchema.Field.string()),
                Set.of("area", "riskTopic"), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String area = ToolArguments.string(args, "area");
        String topic = ToolArguments.string(args, "riskTopic");
        return ToolResult.success(Map.of(
                "planId", "INSP-" + context.taskId().substring(0, Math.min(8, context.taskId().length())),
                "area", area,
                "riskTopic", topic,
                "createdAt", Instant.now(),
                "status", "DRAFT",
                "items", List.of("核对告警与设备状态", "现场复测并保存证据", "完成后由责任人复核关闭")));
    }
}
