package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.rag.Evidence;
import com.mineguard.security.Digests;
import com.mineguard.tool.*;
import com.mineguard.trace.TraceRecorder;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.mineguard.workflow.JdbcAgentTaskStore.*;
import static com.mineguard.workflow.RecoveryCoordinator.UnknownOutcome;

/** 步骤幂等、工具调用和结果检查点；审批及未知写结果各由独立组件检查。 */
@Component
public class StepExecutor {
    private final JdbcAgentTaskStore store;
    private final ToolRegistry tools;
    private final ObjectMapper mapper;
    private final TraceRecorder traces;
    private final ApprovalGuard approvals;
    private final RecoveryCoordinator recovery;

    public StepExecutor(JdbcAgentTaskStore store, ToolRegistry tools, ObjectMapper mapper, TraceRecorder traces,
                        ApprovalGuard approvals, RecoveryCoordinator recovery) {
        this.store = store; this.tools = tools; this.mapper = mapper; this.traces = traces;
        this.approvals = approvals; this.recovery = recovery;
    }

    public void execute(AgentTask task, Lease lease, PlanStep step, boolean approved) {
        if (task.hasCompletedStep(step.id())) {
            // 检查点记录失败、但进程尚未写入终态时，不能把失败当成成功跳过。
            ToolResult previous = task.snapshot().completedSteps().get(step.id()).result();
            if (!previous.success()) {
                if ("OUTCOME_UNKNOWN".equals(previous.errorCode())) throw new UnknownOutcome("已记录的工业写请求结果未知，禁止自动重放");
                throw new IllegalStateException("已记录的步骤失败：" + previous.errorCode());
            }
            return;
        }
        Tool tool = tools.get(step.type().toolName());
        boolean high = tool.category() == ToolCategory.HIGH_RISK;
        String hash = Digests.canonical(mapper, step);
        Optional<Step> existing = store.step(task.getTaskId(), step.id());
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) throw new IllegalStateException("持久化步骤参数已变化");
            if (existing.get().highRisk()) { recovery.recover(task, lease, step, tool, existing.get()); return; }
        }
        if (high) approvals.requireValid(task, approved);
        Instant started = Instant.now();
        store.beginStep(task, lease, step.id(), hash, high, event(TaskEventType.TOOL_STARTED, Map.of("stepId", step.id(), "tool", tool.name(), "args", step.args())));
        ToolResult result = tools.execute(tool.name(), new ToolContext(task.getTaskId(), approved, started, task.getTaskId() + ":" + step.id()), step.args());
        ToolExecutionRecord record = new ToolExecutionRecord(tool.name(), step.args(), tool.category(), result, started);
        task.completeStep(step.id(), record);
        List<EventDraft> events = new ArrayList<>();
        events.add(event(TaskEventType.TOOL_FINISHED, Map.of("stepId", step.id(), "tool", tool.name(), "result", result)));
        if (step.type() == AgentStepType.SEARCH_SAFETY_KNOWLEDGE && result.success() && result.data() instanceof List<?> values) {
            List<Evidence> evidence = values.stream().map(value -> mapper.convertValue(value, Evidence.class)).toList();
            task.addEvidence(evidence); events.add(event(TaskEventType.RAG_RETRIEVED, Map.of("count", evidence.size(), "evidence", evidence)));
            traces.record(task.getTaskId(), "RETRIEVAL", Map.of("evidence", evidence));
        }
        if (step.type() == AgentStepType.VERIFY_DETECTION_TASK) events.add(event(TaskEventType.VERIFICATION, Map.of("success", result.success(), "args", step.args())));
        store.completeStep(task, lease, step.id(), record, events);
        if (!result.success()) {
            if (high && "OUTCOME_UNKNOWN".equals(result.errorCode())) throw new UnknownOutcome("工业写请求结果未知，必须人工核验，禁止自动重试");
            throw new IllegalStateException(step.type() + " failed: " + result.errorMessage());
        }
    }

    private static EventDraft event(TaskEventType type, Map<String, Object> payload) { return new EventDraft(type, payload); }
}
