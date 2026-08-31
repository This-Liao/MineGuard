package com.mineguard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.approval.ApprovalStatus;
import com.mineguard.event.*;
import com.mineguard.rag.Evidence;
import com.mineguard.tool.*;
import com.mineguard.workflow.AgentTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AgentReportComposerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentReportComposer composer = new AgentReportComposer(mapper);
    private AgentTask task() {
        var task = new AgentTask("report-fixture", "分析3号采区事件并给出处置依据");
        task.setPlan(new AgentPlan("analysis", RiskLevel.LOW, List.of()));
        return task;
    }
    private void call(AgentTask task, String tool, Map<String, Object> args, Object data) {
        task.addToolCall(new ToolExecutionRecord(tool, args, ToolCategory.READ, ToolResult.success(data), Instant.parse("2026-08-31T09:36:00Z")));
    }
    private String text(AgentReport report) { return report.sections().stream().flatMap(s -> s.statements().stream()).map(AgentReport.Statement::text).reduce("", (a,b) -> a + b); }
    private void knowledge(AgentTask task) {
        var evidence = new Evidence("K019", "摄像头离线故障处置", "K019-chunk-1", .0466,
                "# 摄像头离线故障处置\n> Synthetic Demo Data — 仅供软件演示。\n\n摄像头离线时，应检查电源和网络连通。恢复连接后验证实时画面与检测任务状态。");
        call(task, "search_safety_knowledge", Map.of("query", "离线处置"), List.of(evidence));
        task.addEvidence(List.of(evidence, evidence));
    }

    @Test void naturalCountsDistributionsAndTraceableQuotesReplaceObjectStrings() {
        var task = task();
        var filter = Map.<String, Object>of("area", "3号采区", "startTime", "2026-08-24T09:36:00Z", "endTime", "2026-08-31T09:36:00Z");
        call(task, "query_safety_events", filter, Map.of("total", 20, "truncated", true, "events", List.of()));
        call(task, "query_alert_statistics", filter, Map.of("total", 20, "filters", filter, "byType", Map.of("PERSONNEL_STAY",5,"DEVICE_OFFLINE",3,"INTRUSION",3,"LOW_LIGHT",3,"NO_HELMET",3,"SMOKING",2,"GAS_WARNING",1)));
        knowledge(task);
        var result = composer.result(task, List.of(), List.of());
        assertThat(text(result.report())).contains("共查询到 20 条", "人员滞留（5 条，占 25.0%）", "北京时间", "2026-08-31 17:36:00", "摄像头离线时，应检查电源和网络连通").doesNotContain("SafetyEvent[", "成功：{", "Synthetic Demo Data");
        var citations = result.report().citations();
        assertThat(citations).hasSize(3);
        assertThat(citations.get(2).documentId()).isEqualTo("K019");
        assertThat(citations.get(2).chunkId()).isEqualTo("K019-chunk-1");
        assertThat(citations.get(2).content()).contains("Synthetic Demo Data");
        assertThat(result.report().notes()).anyMatch(s -> s.contains("合成演示"));
        result.report().sections().stream().flatMap(s -> s.statements().stream()).forEach(s ->
                assertThat(s.citationIds()).allMatch(id -> citations.stream().anyMatch(c -> c.id() == id)));
    }

    @Test void typedRecordsAndDatabaseMapsProduceIdenticalReports() throws Exception {
        var task = task();
        var event = new SafetyEvent("E1", "3号采区", "camera-03", EventType.INTRUSION, Severity.CRITICAL, Instant.parse("2026-08-30T00:00:00Z"), "夹具事件", "RESOLVED");
        call(task, "query_safety_events", Map.of(), Map.of("total", 1, "events", List.of(event), "truncated", false));
        task.setResult(new AgentResult(task.getTaskId(), "历史结果", RiskLevel.LOW, List.of("旧对象字符串"), List.of(), List.of(), List.of(), List.of(), List.of()));
        var before = mapper.writeValueAsString(task.snapshot());
        var persisted = AgentTask.from(mapper.readValue(before, AgentTask.Snapshot.class));
        assertThat(composer.existing(task)).isEqualTo(composer.existing(persisted));
        assertThat(text(composer.existing(task))).contains("高等级或严重事件 1 条", "待处理或处理中的有 0 条");
        assertThat(mapper.writeValueAsString(task.snapshot())).isEqualTo(before);
    }

    @Test void oldResultJsonRemainsReadableWithoutReportField() throws Exception {
        var old = mapper.readValue("{\"taskId\":\"old\",\"summary\":\"历史结果\",\"riskLevel\":\"LOW\"}", AgentResult.class);
        assertThat(old.report()).isNull(); assertThat(old.findings()).isEmpty();
        var task = task(); task.setResult(old);
        call(task, "get_device_status", Map.of("deviceId", "camera-03"), Map.of("deviceId", "camera-03", "status", "ONLINE"));
        assertThat(text(composer.existing(task))).contains("camera-03", "在线");
        assertThat(task.getResult()).isSameAs(old);
    }

    @Test void emptyResultsDoNotInventSafetyOrKnowledge() {
        var task = task();
        call(task, "query_safety_events", Map.of(), Map.of("total", 0, "events", List.of()));
        call(task, "query_alert_statistics", Map.of(), Map.of("total", 0, "byType", Map.of()));
        call(task, "search_safety_knowledge", Map.of(), List.of());
        var result = composer.result(task, List.of(), List.of());
        assertThat(text(result.report())).contains("未检出不等于现场不存在风险").doesNotContain("NaN", "Infinity", "摄像头离线时");
        assertThat(result.report().notes()).anyMatch(s -> s.contains("未取得可引用"));
    }

    @Test void truncatedSeverityIsOnlyAboutReturnedSample() {
        var task = task();
        call(task, "query_safety_events", Map.of(), Map.of("total", 200, "truncated", true,
                "events", List.of(Map.of("severity", "HIGH", "status", "OPEN"), Map.of("severity", "CRITICAL", "status", "UNKNOWN"))));
        var report = composer.result(task, List.of(), List.of()).report();
        assertThat(text(report)).contains("当前返回的 2 条明细中", "处理中的有 1 条").doesNotContain("上述事件中");
        assertThat(report.notes()).anyMatch(s -> s.contains("不外推"));
    }

    @Test void tiedFrequenciesAndInconsistentTotalsAreHandledHonestly() {
        var task = task();
        call(task, "query_alert_statistics", Map.of(), Map.of("total", 4, "byType", Map.of("INTRUSION",2,"SMOKING",2)));
        var text = text(composer.result(task, List.of(), List.of()).report());
        assertThat(text).contains("区域入侵、吸烟", "各占 50.0%");
        var mismatch = task();
        call(mismatch, "query_alert_statistics", Map.of(), Map.of("total", 10, "byType", Map.of("INTRUSION",2,"SMOKING",2)));
        assertThat(text(composer.result(mismatch, List.of(), List.of()).report())).doesNotContain("%");
    }

    @Test void draftAndSuccessfulWriteDoNotImplySiteWorkOrIndependentVerification() {
        var task = task();
        call(task, "create_inspection_plan", Map.of(), Map.of("planId", "P1", "area", "3号采区", "status", "DRAFT", "items", List.of("核对事件")));
        call(task, "start_detection_task", Map.of("cameraId", "camera-03", "algorithm", "intrusion_detection"), Map.of("status", "RUNNING"));
        String text = text(composer.result(task, List.of(), List.of()).report());
        assertThat(text).contains("草案", "尚不表示已派发", "以后续独立核验为准").doesNotContain("独立核验确认");
        call(task, "verify_detection_task", Map.of("cameraId", "camera-03", "algorithm", "intrusion_detection", "expectedStatus", "RUNNING"), Map.of("verified", true));
        assertThat(text(composer.result(task, List.of(), List.of()).report())).contains("独立核验确认", "运行中");
    }

    @Test void rejectedTaskRetainsRejectionMeaning() {
        var task = task();
        task.setApproval(new ApprovalDecision(ApprovalStatus.REJECTED, "reviewer", "未获授权", Instant.now()));
        task.setResult(new AgentResult(task.getTaskId(), "操作已被拒绝", RiskLevel.HIGH, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThat(composer.existing(task).summary()).contains("操作已被拒绝", "未执行");
    }

    @Test void missingResultsAndFailedCallsNeverBecomeSuccessFacts() {
        var task = task();
        assertThatThrownBy(() -> composer.existing(task)).hasMessageContaining("尚无完成结果");
        task.addToolCall(new ToolExecutionRecord("get_device_status", Map.of(), ToolCategory.READ, ToolResult.failure("ERROR", "失败"), Instant.now()));
        var report = composer.result(task, List.of(), List.of()).report();
        assertThat(report.sections()).isEmpty(); assertThat(report.citations()).isEmpty();
        assertThat(report.notes()).anyMatch(s -> s.contains("未成功"));
    }
}
