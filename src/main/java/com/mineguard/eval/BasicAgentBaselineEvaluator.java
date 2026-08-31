package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 简化确定性基线：仅静态匹配关键词选择的单个工具，不执行工具、审批或验证。 */
@Component
public class BasicAgentBaselineEvaluator {
    private final ObjectMapper mapper;
    public BasicAgentBaselineEvaluator(ObjectMapper mapper) { this.mapper = mapper; }

    public Result evaluate(Path casesPath) {
        try {
            List<AgentEvaluator.Case> cases = mapper.readValue(casesPath.toFile(), new TypeReference<>() {});
            int selectionHits = 0, outcomeHits = 0;
            for (AgentEvaluator.Case testCase : cases) {
                String selected = selectOne(testCase.query());
                boolean selection = testCase.expectedTools().size() == 1 && testCase.expectedTools().contains(selected);
                if (selection) selectionHits++;
                boolean outcome = selection && !testCase.approvalRequired() && "COMPLETED".equals(testCase.expectedOutcome());
                if (outcome) outcomeHits++;
            }
            return new Result(cases.size(), rate(outcomeHits, cases.size()), rate(selectionHits, cases.size()), 1.0, 1.0);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read baseline cases", ex);
        }
    }

    private String selectOne(String query) {
        String value = query.toLowerCase(Locale.ROOT);
        if (value.contains("启动") || value.contains("start")) return "start_detection_task";
        if (value.contains("停止") || value.contains("关闭") || value.contains("stop")) return "stop_detection_task";
        if (value.contains("camera") || value.contains("摄像头")) return "get_device_status";
        if (value.contains("统计") || value.contains("分析") || value.contains("事件") || value.contains("告警")) return "query_safety_events";
        return "search_safety_knowledge";
    }

    private double rate(int value, int count) { return count == 0 ? 0 : Math.round((double) value / count * 10_000d) / 10_000d; }
    public record Result(int caseCount, double taskSuccessRate, double toolSelectionAccuracy,
                         double averageToolCalls, double relativeKnowledgeInjection) {}
}
