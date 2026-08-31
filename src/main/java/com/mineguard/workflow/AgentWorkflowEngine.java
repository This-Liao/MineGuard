package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.approval.ApprovalStatus;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.config.RuntimeProperties;
import com.mineguard.rag.Evidence;
import com.mineguard.security.*;
import com.mineguard.tool.*;
import com.mineguard.trace.TraceRecorder;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static com.mineguard.workflow.JdbcAgentTaskStore.*;

/** 数据库驱动的可恢复工作流；内存线程只执行当前有效租约下的工作。 */
@Service
public class AgentWorkflowEngine {
    private final JdbcAgentTaskStore store;
    private final StructuredPlanner planner;
    private final ToolRegistry tools;
    private final TraceRecorder traces;
    private final ExecutorService executor;
    private final RuntimeProperties runtime;
    private final TaskAccessPolicy policy;
    private final ObjectMapper mapper;
    private final IdentityService identities;
    private final com.mineguard.device.IndustrialGateway gateway;
    private final AgentReportComposer reports;
    private final int capacity;
    private final String worker;
    private final Map<String, Lease> active = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public AgentWorkflowEngine(JdbcAgentTaskStore store, StructuredPlanner planner, ToolRegistry tools, TraceRecorder traces,
                               ExecutorService workflowExecutor, RuntimeProperties runtime, TaskAccessPolicy policy,
                               ObjectMapper mapper, MineGuardProperties config, IdentityService identities, com.mineguard.device.IndustrialGateway gateway, AgentReportComposer reports) {
        this.store = store; this.planner = planner; this.tools = tools; this.traces = traces; this.executor = workflowExecutor;
        this.runtime = runtime; this.policy = policy; this.mapper = mapper; this.capacity = Math.max(1, config.workflowExecutorThreads());
        this.identities = identities;
        this.gateway = gateway;
        this.reports = reports;
        this.worker = runtime.nodeId() + "-" + UUID.randomUUID();
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

    @Scheduled(fixedDelayString = "${mineguard.runtime.poll-ms:200}")
    public synchronized void dispatch() {
        if (stopped || !runtime.schedulerEnabled()) return;
        for (String id : store.candidates(capacity * 2)) {
            if (active.size() >= capacity) break;
            if (active.containsKey(id)) continue;
            store.claim(id, worker, runtime.leaseSeconds()).ifPresent(lease -> {
                active.put(id, lease);
                try { executor.submit(() -> runLease(lease)); }
                catch (RuntimeException ex) { active.remove(id); store.release(lease); }
            });
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        if (!stopped) active.values().forEach(lease -> {
            try { store.renew(lease, runtime.leaseSeconds()); } catch (RuntimeException ignored) { /* 无法续约时，后续提交由数据库拒绝。 */ }
        });
    }
    @PreDestroy public void stop() { stopped = true; executor.shutdownNow(); }

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
                        for (PlanStep step : task.getPlan().steps()) if (tools.get(step.type().toolName()).category() != ToolCategory.HIGH_RISK && step.type() != AgentStepType.VERIFY_DETECTION_TASK) execute(task, lease, step, false);
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
                        for (PlanStep step : risky(task)) execute(task, lease, step, true);
                        move(task, lease, AgentTaskState.VERIFYING, List.of());
                    }
                    case VERIFYING -> {
                        List<String> verification = new ArrayList<>();
                        for (PlanStep step : risky(task)) {
                            Map<String, Object> args = new LinkedHashMap<>(step.args());
                            args.put("expectedStatus", step.type() == AgentStepType.START_DETECTION_TASK ? "RUNNING" : "STOPPED");
                            execute(task, lease, new PlanStep("verify:" + step.id(), AgentStepType.VERIFY_DETECTION_TASK, "独立查询操作结果", args), false);
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
            active.remove(task.getTaskId(), lease);
            store.release(lease);
        }
    }

    private void execute(AgentTask task, Lease lease, PlanStep step, boolean approved) {
        if (task.hasCompletedStep(step.id())) {
            // 检查点已记录失败、但进程尚未写入终态时，恢复不能把失败当成成功跳过。
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
            if (existing.get().highRisk()) {
                // 只有接收端按原操作键返回可核验回执，才能补记已完成步骤；没有回执时不发送第二次写请求。
                java.util.Optional<com.mineguard.device.DetectionTask> receipt;
                try { receipt = gateway.operationReceipt(existing.get().operationKey()); }
                catch (Exception ex) { throw new UnknownOutcome("外部回执查询不可用，保留待核验状态，禁止重放写请求"); }
                String expected = step.type() == AgentStepType.START_DETECTION_TASK ? "RUNNING" : "STOPPED";
                if (receipt.isPresent() && receipt.get().cameraId().equals(step.args().get("cameraId"))
                        && receipt.get().algorithm().equals(step.args().get("algorithm")) && receipt.get().status().equals(expected)) {
                    var record = new ToolExecutionRecord(tool.name(), step.args(), tool.category(), ToolResult.success(receipt.get()), Instant.now());
                    task.completeStep(step.id(), record);
                    store.completeStep(task, lease, step.id(), record, List.of(event(TaskEventType.TOOL_FINISHED, Map.of("stepId", step.id(), "tool", tool.name(), "result", record.result(), "recoveredFromReceipt", true))));
                    return;
                }
                throw new UnknownOutcome("高风险步骤已发出但没有完整检查点，必须核验外部回执；禁止自动重放");
            }
        }
        if (high) {
            if (!approved || task.getApproval() == null || task.getApproval().status() != ApprovalStatus.APPROVED
                    || !Objects.equals(task.getApprovedPlanHash(), Digests.canonical(mapper, task.getPlan()))
                    || task.getApprovalValidUntil() == null || !task.getApprovalValidUntil().isAfter(store.now())) {
                throw new IllegalStateException("审批缺失、过期或计划参数发生变化");
            }
            if (!"evaluation".equals(task.getTenantId())) {
                Actor approver = identities.enabledUser(task.getApproval().decidedBy()).orElseThrow(() -> new IllegalStateException("审批人已停用"));
                policy.requireApproval(approver, task);
            }
        }
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
    private static final class UnknownOutcome extends IllegalStateException { UnknownOutcome(String message) { super(message); } }
}
