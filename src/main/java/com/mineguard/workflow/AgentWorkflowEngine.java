package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.config.RuntimeProperties;
import com.mineguard.security.*;
import com.mineguard.tool.*;
import com.mineguard.trace.TraceRecorder;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.mineguard.workflow.JdbcAgentTaskStore.*;
import static com.mineguard.workflow.RecoveryCoordinator.UnknownOutcome;

/** 工作流状态机与应用入口；调度、步骤执行、审批检查和恢复各有独立职责。 */
@Service
public class AgentWorkflowEngine {
    private final JdbcAgentTaskStore store;
    private final StructuredPlanner planner;
    private final ToolRegistry tools;
    private final TraceRecorder traces;
    private final RuntimeProperties runtime;
    private final TaskAccessPolicy policy;
    private final ObjectMapper mapper;
    private final AgentReportComposer reports;
    private final StepExecutor steps;

    public AgentWorkflowEngine(JdbcAgentTaskStore store, StructuredPlanner planner, ToolRegistry tools, TraceRecorder traces,
                               RuntimeProperties runtime, TaskAccessPolicy policy, ObjectMapper mapper,
                               AgentReportComposer reports, StepExecutor steps) {
        this.store = store; this.planner = planner; this.tools = tools; this.traces = traces;
        this.runtime = runtime; this.policy = policy; this.mapper = mapper; this.reports = reports; this.steps = steps;
    }

    public AgentTask create(String query) { return create(query, Actor.internal("evaluation-runner"), UUID.randomUUID().toString()); }
    public AgentTask create(String query, Actor actor, String key) {
        policy.requireRole(actor, Role.OPERATOR);
        if (query == null || query.isBlank() || query.length() > 8000) throw new IllegalArgumentException("查询内容必须为 1 至 8000 字符");
        return store.create(query.trim(), actor, key);
    }
    public AgentTask get(String id) { return store.findById(id).orElseThrow(() -> new NoSuchElementException("任务不存在")); }
    public AgentTask get(String id, Actor actor) { AgentTask task = get(id); policy.requireRead(actor, task); return task; }
    public List<AgentTask> list() { return store.findAll(); }
    public List<AgentTask> list(Actor actor) { return store.visibleTo(actor); }

    // 仅供同进程评测器使用；HTTP 控制器必须传入认证后的 Actor。
    public AgentTask approve(String id, String actor, String reason) { return decide(id, Actor.internal(actor), true, reason, get(id).getPlanHash(), UUID.randomUUID().toString()); }
    public AgentTask reject(String id, String actor, String reason) { return decide(id, Actor.internal(actor), false, reason, get(id).getPlanHash(), UUID.randomUUID().toString()); }
    public AgentTask decide(String id, Actor actor, boolean approved, String reason, String hash, String key) {
        if (hash == null || reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("必须提供计划摘要和有效审批理由");
        AgentTask task = store.decide(id, actor, key, approved ? "APPROVED" : "REJECTED", reason, hash, runtime.approvalSeconds(), t -> policy.requireApproval(actor, t));
        traces.record(id, "APPROVAL", Map.of("decision", task.getApproval().status(), "actor", actor.userId()));
        if (task.getState().terminal()) traces.complete(id, task.getResult());
        return task;
    }

    public void runLease(Lease lease) {
        AgentTask task = get(lease.taskId());
        try {
            if (traces.get(task.getTaskId()).isEmpty()) traces.start(task.getTaskId(), task.getUserQuery());
            while (!task.getState().terminal() && task.getState() != AgentTaskState.WAITING_APPROVAL) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("工作节点停止");
                switch (task.getState()) {
                    case CREATED -> move(task, lease, AgentTaskState.PLANNING, List.of());
                    case PLANNING -> {
                        AgentPlan plan = planner.plan(task.getUserQuery());
                        task.setPlan(plan); task.setPlanHash(Digests.canonical(mapper, plan));
                        move(task, lease, AgentTaskState.RETRIEVING, List.of(event(TaskEventType.PLAN_CREATED, Map.of("plan", plan, "model", planner.model().providerName()))));
                    }
                    case RETRIEVING -> {
                        for (PlanStep step : task.getPlan().steps()) if (tools.get(step.type().toolName()).category() != ToolCategory.HIGH_RISK && step.type() != AgentStepType.VERIFY_DETECTION_TASK) steps.execute(task, lease, step, false);
                        move(task, lease, AgentTaskState.ANALYZING, List.of());
                    }
                    case ANALYZING -> {
                        if (risky(task).isEmpty()) finish(task, lease, List.of(), List.of());
                        else {
                            task.setApproval(ApprovalDecision.pending());
                            move(task, lease, AgentTaskState.WAITING_APPROVAL, List.of(event(TaskEventType.WAITING_APPROVAL,
                                    Map.of("message", "高风险操作需要其他审批员批准", "planHash", task.getPlanHash(), "operations", risky(task)))));
                            traces.record(task.getTaskId(), "APPROVAL", Map.of("decision", "PENDING"));
                        }
                    }
                    case EXECUTING -> {
                        for (PlanStep step : risky(task)) steps.execute(task, lease, step, true);
                        move(task, lease, AgentTaskState.VERIFYING, List.of());
                    }
                    case VERIFYING -> {
                        List<String> verification = new ArrayList<>();
                        for (PlanStep step : risky(task)) {
                            Map<String, Object> args = new LinkedHashMap<>(step.args());
                            args.put("expectedStatus", step.type() == AgentStepType.START_DETECTION_TASK ? "RUNNING" : "STOPPED");
                            steps.execute(task, lease, new PlanStep("verify:" + step.id(), AgentStepType.VERIFY_DETECTION_TASK, "独立查询操作结果", args), false);
                            verification.add("已验证 " + args.get("cameraId") + "/" + args.get("algorithm") + " = " + args.get("expectedStatus"));
                        }
                        finish(task, lease, risky(task).stream().map(s -> s.type().toolName() + " " + s.args()).toList(), verification);
                    }
                    default -> throw new IllegalStateException("不支持的任务状态");
                }
            }
        } catch (LeaseLostException ignored) {
            // 旧节点不得将新节点的任务标记失败，也不能继续执行后续步骤。
        } catch (Exception ex) {
            try {
                task.setError(ex.getMessage() == null ? "工作流执行异常" : ex.getMessage());
                move(task, lease, ex instanceof UnknownOutcome ? AgentTaskState.RECOVERY_REQUIRED : AgentTaskState.FAILED,
                        List.of(event(TaskEventType.ERROR, Map.of("message", task.getError()))));
                traces.record(task.getTaskId(), "ERROR", Map.of("message", task.getError()));
                traces.complete(task.getTaskId(), Map.of("error", task.getError()));
            } catch (LeaseLostException ignored) { /* 新租约拥有者负责恢复。 */ }
        } finally {
            store.release(lease);
        }
    }

    private List<PlanStep> risky(AgentTask task) { return task.getPlan().steps().stream().filter(s -> tools.get(s.type().toolName()).category() == ToolCategory.HIGH_RISK).toList(); }
    private void finish(AgentTask task, Lease lease, List<String> executed, List<String> verification) {
        task.setResult(reports.result(task, executed, verification));
        move(task, lease, AgentTaskState.COMPLETED, List.of(event(TaskEventType.FINAL_RESULT, Map.of("result", task.getResult()))));
        traces.complete(task.getTaskId(), task.getResult());
    }
    private void move(AgentTask task, Lease lease, AgentTaskState next, List<EventDraft> additional) {
        AgentTaskState from = task.getState(); task.transitionTo(next);
        List<EventDraft> events = new ArrayList<>(); events.add(event(TaskEventType.TASK_STATE_CHANGED, Map.of("from", from, "to", next))); events.addAll(additional);
        store.checkpoint(task, lease, events);
        traces.record(task.getTaskId(), "STATE_TRANSITION", Map.of("from", from, "to", next));
    }
    private static EventDraft event(TaskEventType type, Map<String, Object> payload) { return new EventDraft(type, payload); }
}
