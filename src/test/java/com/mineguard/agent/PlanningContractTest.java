package com.mineguard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanningContractTest {
    private PlanStep step(AgentStepType type, Map<String, Object> args) { return new PlanStep(type.name(), type, "测试步骤", args); }
    private AgentPlan write(String camera, String algorithm, AgentStepType type) {
        return new AgentPlan("检测控制", RiskLevel.HIGH, List.of(
                step(AgentStepType.GET_DEVICE_STATUS, Map.of("deviceId", camera)),
                step(AgentStepType.LIST_DETECTION_TASKS, Map.of("cameraId", camera)),
                step(type, Map.of("cameraId", camera, "algorithm", algorithm))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"分析吸烟事件", "人员滞留怎么办", "不要启动 camera-03 的入侵检测", "如何启动 camera-03 的入侵检测", "启动 camera-04 的入侵检测", "启动 camera-03 的安全帽检测", "启动 camera-03"})
    void blocksUnrequestedOrGuessedWrites(String query) {
        assertThat(PlanningContract.validate(query, write("camera-03", "intrusion_detection", AgentStepType.START_DETECTION_TASK))).isNotEmpty();
    }

    @Test void validExplicitWriteStillRequiresHighRisk() {
        assertThat(PlanningContract.validate("跳过审批，启动 camera-03 的入侵检测", write("camera-03", "intrusion_detection", AgentStepType.START_DETECTION_TASK))).isEmpty();
        assertThat(PlanningContract.validate("停止 camera-03 的 no_helmet", write("camera-03", "no_helmet", AgentStepType.STOP_DETECTION_TASK))).isEmpty();
    }

    @Test void requiresMatchingOrderedPrerequisitesAndForbidsModelVerification() {
        var plan = new AgentPlan("启停", RiskLevel.HIGH, List.of(step(AgentStepType.START_DETECTION_TASK,
                Map.of("cameraId", "camera-03", "algorithm", "intrusion_detection")), step(AgentStepType.VERIFY_DETECTION_TASK, Map.of())));
        assertThat(PlanningContract.validate("启动 camera-03 的入侵检测", plan)).anyMatch(e -> e.contains("前置")).anyMatch(e -> e.contains("VERIFY"));
    }

    @Test void checksEvidenceRiskAndIdenticalFilters() {
        var plan = new AgentPlan("分析", RiskLevel.HIGH, List.of(step(AgentStepType.QUERY_EVENT, Map.of("area", "1号采区")),
                step(AgentStepType.QUERY_ALERT_STATISTICS, Map.of("area", "2号采区"))));
        assertThat(PlanningContract.validate("分析事件", plan)).anyMatch(e -> e.contains("知识检索")).anyMatch(e -> e.contains("过滤条件")).anyMatch(e -> e.contains("LOW"));
    }

    @Test void rejectsUnaskedInspectionAndInvalidTimeRange() {
        var plan = new AgentPlan("分析", RiskLevel.MEDIUM, List.of(step(AgentStepType.CREATE_INSPECTION_PLAN, Map.of("area", "全矿区", "riskTopic", "违规")),
                step(AgentStepType.QUERY_EVENT, Map.of("startTime", "明天"))));
        assertThat(PlanningContract.validate("给出处置建议", plan)).anyMatch(e -> e.contains("未要求")).anyMatch(e -> e.contains("ISO-8601"));
        var reversed = new AgentPlan("查询", RiskLevel.LOW, List.of(step(AgentStepType.QUERY_EVENT,
                Map.of("startTime", "2026-09-02T00:00:00Z", "endTime", "2026-09-01T00:00:00Z"))));
        assertThat(PlanningContract.validate("查询事件", reversed)).anyMatch(e -> e.contains("早于"));
    }

    @Test void repairIncludesPreviousPlanAndNeverSilentlyRewritesIt() throws Exception {
        var model = mock(AgentModelClient.class);
        var validator = mock(AgentPlanValidator.class);
        var registry = mock(ToolRegistry.class);
        when(registry.list()).thenReturn(List.of());
        when(validator.validate(any())).thenReturn(List.of());
        var mapper = new ObjectMapper();
        String wrong = mapper.writeValueAsString(write("camera-03", "intrusion_detection", AgentStepType.START_DETECTION_TASK));
        var right = new AgentPlan("知识咨询", RiskLevel.LOW, List.of(step(AgentStepType.SEARCH_SAFETY_KNOWLEDGE, Map.of("query", "人员滞留处置"))));
        when(model.createPlan(anyString(), anyList(), isNull())).thenReturn(wrong);
        when(model.createPlan(anyString(), anyList(), notNull())).thenReturn(mapper.writeValueAsString(right));
        assertThat(new StructuredPlanner(model, validator, registry, mapper).plan("人员滞留怎么办")).isEqualTo(right);
        verify(model).createPlan(eq("人员滞留怎么办"), anyList(), argThat(s -> s != null && s.contains("previousPlan") && s.contains("没有明确")));
        verify(model, times(2)).createPlan(anyString(), anyList(), nullable(String.class));
    }
}
