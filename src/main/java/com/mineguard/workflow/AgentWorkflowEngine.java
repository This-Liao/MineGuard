package com.mineguard.workflow;

import com.mineguard.agent.*;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.rag.Evidence;
import com.mineguard.tool.*;
import com.mineguard.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;

@Service
public class AgentWorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowEngine.class);
    private final AgentTaskStore store;
    private final StructuredPlanner planner;
    private final ToolRegistry tools;
    private final TaskEventPublisher events;
    private final TraceRecorder traces;
    private final ExecutorService executor;

    public AgentWorkflowEngine(AgentTaskStore store, StructuredPlanner planner, ToolRegistry tools,
                               TaskEventPublisher events, TraceRecorder traces, ExecutorService workflowExecutor) {
        this.store = store;
        this.planner = planner;
        this.tools = tools;
        this.events = events;
        this.traces = traces;
        this.executor = workflowExecutor;
    }

    public AgentTask create(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) throw new IllegalArgumentException("query must not be blank");
        AgentTask task = store.save(new AgentTask(UUID.randomUUID().toString(), userQuery.trim()));
        traces.start(task.getTaskId(), task.getUserQuery());
        executor.submit(() -> runInitial(task));
        return task;
    }

    public AgentTask approve(String taskId, String actor, String reason) {
        AgentTask task = requireTask(taskId);
        synchronized (task) {
            if (task.getState() != AgentTaskState.WAITING_APPROVAL) {
                throw new IllegalStateException("task is not waiting for approval");
            }
            task.setApproval(ApprovalDecision.approved(defaultActor(actor), reason));
            events.publish(taskId, TaskEventType.APPROVED, Map.of("actor", defaultActor(actor), "reason", safe(reason)));
            traces.record(taskId, "APPROVAL", Map.of("decision", "APPROVED", "actor", defaultActor(actor), "reason", safe(reason)));
            executor.submit(() -> runApproved(task));
        }
        return task;
    }

    public AgentTask reject(String taskId, String actor, String reason) {
        AgentTask task = requireTask(taskId);
        synchronized (task) {
            if (task.getState() != AgentTaskState.WAITING_APPROVAL) {
                throw new IllegalStateException("task is not waiting for approval");
            }
            task.setApproval(ApprovalDecision.rejected(defaultActor(actor), reason));
            events.publish(taskId, TaskEventType.REJECTED, Map.of("actor", defaultActor(actor), "reason", safe(reason)));
            traces.record(taskId, "APPROVAL", Map.of("decision", "REJECTED", "actor", defaultActor(actor), "reason", safe(reason)));
            AgentResult result = resultFor(task, "操作已被拒绝，未执行系统变更。", List.of(), List.of(),
                    List.of("人工审批拒绝了高风险操作。"));
            task.setResult(result);
            transition(task, AgentTaskState.COMPLETED);
            finish(task);
        }
        return task;
    }

    public AgentTask get(String taskId) { return requireTask(taskId); }
    public List<AgentTask> list() { return store.findAll(); }

    private void runInitial(AgentTask task) {
        try {
            transition(task, AgentTaskState.PLANNING);
            AgentPlan plan = planner.plan(task.getUserQuery());
            task.setPlan(plan);
            events.publish(task.getTaskId(), TaskEventType.PLAN_CREATED, Map.of("plan", plan, "model", planner.model().providerName()));
            traces.record(task.getTaskId(), "PLAN_CREATED", Map.of("plan", plan, "model", planner.model().providerName()));
            transition(task, AgentTaskState.RETRIEVING);
            for (PlanStep step : plan.steps()) {
                Tool tool = tools.get(step.type().toolName());
                if (tool.category() != ToolCategory.HIGH_RISK && step.type() != AgentStepType.VERIFY_DETECTION_TASK) {
                    ToolResult result = execute(task, step, false);
                    if (!result.success()) throw new IllegalStateException(step.type() + " failed: " + result.errorMessage());
                }
            }
            transition(task, AgentTaskState.ANALYZING);
            boolean requiresApproval = plan.steps().stream()
                    .anyMatch(step -> tools.get(step.type().toolName()).category() == ToolCategory.HIGH_RISK);
            if (requiresApproval) {
                task.setApproval(ApprovalDecision.pending());
                transition(task, AgentTaskState.WAITING_APPROVAL);
                events.publish(task.getTaskId(), TaskEventType.WAITING_APPROVAL,
                        Map.of("message", "高风险操作需要人工审批", "operations", highRiskNames(plan)));
                traces.record(task.getTaskId(), "APPROVAL", Map.of("decision", "PENDING", "operations", highRiskNames(plan)));
            } else {
                task.setResult(resultFor(task, "任务已完成，结果来自结构化工具与检索证据。", List.of(), List.of(), List.of()));
                transition(task, AgentTaskState.COMPLETED);
                finish(task);
            }
        } catch (Exception ex) {
            fail(task, ex);
        }
    }

    private void runApproved(AgentTask task) {
        try {
            transition(task, AgentTaskState.EXECUTING);
            List<String> executed = new ArrayList<>();
            List<PlanStep> riskySteps = task.getPlan().steps().stream()
                    .filter(step -> tools.get(step.type().toolName()).category() == ToolCategory.HIGH_RISK).toList();
            for (PlanStep step : riskySteps) {
                ToolResult result = execute(task, step, true);
                if (!result.success()) throw new IllegalStateException(step.type() + " failed: " + result.errorMessage());
                executed.add(step.type().toolName() + " " + step.args());
            }
            transition(task, AgentTaskState.VERIFYING);
            List<String> verification = new ArrayList<>();
            for (PlanStep step : riskySteps) {
                Map<String, Object> args = new LinkedHashMap<>(step.args());
                args.put("expectedStatus", step.type() == AgentStepType.START_DETECTION_TASK ? "RUNNING" : "STOPPED");
                PlanStep verify = new PlanStep(step.id() + "-verify", AgentStepType.VERIFY_DETECTION_TASK,
                        "验证高风险操作结果", args);
                ToolResult result = execute(task, verify, true);
                events.publish(task.getTaskId(), TaskEventType.VERIFICATION,
                        Map.of("success", result.success(), "args", args));
                if (!result.success()) throw new IllegalStateException("verification failed: " + result.errorMessage());
                verification.add("已验证 " + args.get("cameraId") + "/" + args.get("algorithm") + " = " + args.get("expectedStatus"));
            }
            task.setResult(resultFor(task, "高风险操作已获批准、执行并通过独立验证。", executed, verification, List.of()));
            transition(task, AgentTaskState.COMPLETED);
            finish(task);
        } catch (Exception ex) {
            fail(task, ex);
        }
    }

    private ToolResult execute(AgentTask task, PlanStep step, boolean approved) {
        Instant startedAt = Instant.now();
        events.publish(task.getTaskId(), TaskEventType.TOOL_STARTED,
                Map.of("stepId", step.id(), "tool", step.type().toolName(), "args", step.args()));
        Tool tool = tools.get(step.type().toolName());
        ToolResult result = tools.execute(tool.name(), new ToolContext(task.getTaskId(), approved, startedAt), step.args());
        task.addToolCall(new ToolExecutionRecord(tool.name(), step.args(), tool.category(), result, startedAt));
        events.publish(task.getTaskId(), TaskEventType.TOOL_FINISHED,
                Map.of("stepId", step.id(), "tool", tool.name(), "result", result));
        if (step.type() == AgentStepType.SEARCH_SAFETY_KNOWLEDGE && result.success() && result.data() instanceof List<?> list) {
            List<Evidence> evidence = list.stream().filter(Evidence.class::isInstance).map(Evidence.class::cast).toList();
            task.addEvidence(evidence);
            events.publish(task.getTaskId(), TaskEventType.RAG_RETRIEVED,
                    Map.of("count", evidence.size(), "evidence", evidence));
            traces.record(task.getTaskId(), "RETRIEVAL", Map.of("query", step.args().getOrDefault("query", ""), "evidence", evidence));
        }
        return result;
    }

    private AgentResult resultFor(AgentTask task, String summary, List<String> executed, List<String> verification,
                                  List<String> warnings) {
        List<String> findings = task.getToolCalls().stream().map(call -> summarize(call.toolName(), call.result())).toList();
        List<String> actions = new ArrayList<>();
        if (!task.getEvidence().isEmpty()) actions.add("按检索到的合成安全知识进行现场复核，知识文本不替代正式规程。");
        if (task.getPlan() != null && task.getPlan().riskLevel() != RiskLevel.LOW) actions.add("由有权限的现场责任人确认后续处置与关闭条件。");
        return new AgentResult(task.getTaskId(), summary,
                task.getPlan() == null ? RiskLevel.LOW : task.getPlan().riskLevel(), findings, actions,
                task.getEvidence(), executed, verification, warnings);
    }

    private String summarize(String toolName, ToolResult result) {
        if (!result.success()) return toolName + " 失败：" + result.errorCode();
        String value = String.valueOf(result.data()).replaceAll("\\s+", " ");
        if (value.length() > 260) value = value.substring(0, 260) + "…";
        return toolName + " 成功（" + result.elapsedMs() + " ms）：" + value;
    }

    private List<String> highRiskNames(AgentPlan plan) {
        return plan.steps().stream().filter(step -> tools.get(step.type().toolName()).category() == ToolCategory.HIGH_RISK)
                .map(step -> step.type().toolName()).toList();
    }

    private void transition(AgentTask task, AgentTaskState next) {
        AgentTaskState previous = task.getState();
        task.transitionTo(next);
        events.publish(task.getTaskId(), TaskEventType.TASK_STATE_CHANGED, Map.of("from", previous, "to", next));
        traces.record(task.getTaskId(), "STATE_TRANSITION", Map.of("from", previous, "to", next));
    }

    private void finish(AgentTask task) {
        events.publish(task.getTaskId(), TaskEventType.FINAL_RESULT, Map.of("result", task.getResult()));
        traces.complete(task.getTaskId(), task.getResult());
    }

    private void fail(AgentTask task, Exception ex) {
        log.warn("Task {} failed in state {}: {}", task.getTaskId(), task.getState(), ex.getMessage());
        task.setError(ex.getMessage());
        try {
            if (!task.getState().terminal()) transition(task, AgentTaskState.FAILED);
        } catch (Exception transitionFailure) {
            log.error("Could not transition task {} to FAILED", task.getTaskId(), transitionFailure);
        }
        events.publish(task.getTaskId(), TaskEventType.ERROR, Map.of("message", safe(ex.getMessage())));
        traces.record(task.getTaskId(), "ERROR", Map.of("message", safe(ex.getMessage()), "type", ex.getClass().getSimpleName()));
        traces.complete(task.getTaskId(), Map.of("error", safe(ex.getMessage())));
    }

    private AgentTask requireTask(String taskId) {
        return store.findById(taskId).orElseThrow(() -> new NoSuchElementException("task not found: " + taskId));
    }

    private String defaultActor(String actor) { return actor == null || actor.isBlank() ? "demo-operator" : actor.trim(); }
    private String safe(String value) { return value == null ? "" : value; }
}
