package com.mineguard.workflow;

import com.mineguard.agent.AgentPlan;
import com.mineguard.agent.AgentResult;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.rag.Evidence;
import com.mineguard.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentTask {
    private final String taskId;
    private final String userQuery;
    private AgentTaskState state = AgentTaskState.CREATED;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;
    private AgentPlan plan;
    private final List<ToolExecutionRecord> toolCalls = new ArrayList<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private ApprovalDecision approval;
    private AgentResult result;
    private String error;
    private String ownerId = "evaluation-runner";
    private String tenantId = "evaluation";
    private long version;
    private String planHash;
    private String approvedPlanHash;
    private Instant approvalValidUntil;
    private final Map<String, ToolExecutionRecord> completedSteps = new LinkedHashMap<>();

    public AgentTask(String taskId, String userQuery) {
        this.taskId = taskId;
        this.userQuery = userQuery;
    }

    public synchronized void transitionTo(AgentTaskState next) {
        if (!state.canTransitionTo(next)) throw new IllegalStateException("illegal task transition: " + state + " -> " + next);
        state = next;
        updatedAt = Instant.now();
    }

    public synchronized void setPlan(AgentPlan plan) { this.plan = plan; updatedAt = Instant.now(); }
    public synchronized void addToolCall(ToolExecutionRecord call) { toolCalls.add(call); updatedAt = Instant.now(); }
    public synchronized void addEvidence(List<Evidence> values) { evidence.addAll(values); updatedAt = Instant.now(); }
    public synchronized void setApproval(ApprovalDecision approval) { this.approval = approval; updatedAt = Instant.now(); }
    public synchronized void setResult(AgentResult result) { this.result = result; updatedAt = Instant.now(); }
    public synchronized void setError(String error) { this.error = error; updatedAt = Instant.now(); }
    public synchronized void setIdentity(String owner, String tenant) { ownerId = owner; tenantId = tenant; }
    public synchronized void setVersion(long value) { version = value; }
    public synchronized void setPlanHash(String value) { planHash = value; }
    public synchronized void bindApproval(String hash, Instant validUntil) { approvedPlanHash = hash; approvalValidUntil = validUntil; }
    public synchronized void completeStep(String id, ToolExecutionRecord call) {
        if (!completedSteps.containsKey(id)) { completedSteps.put(id, call); addToolCall(call); }
    }
    public synchronized boolean hasCompletedStep(String id) { return completedSteps.containsKey(id); }

    public String getTaskId() { return taskId; }
    public String getUserQuery() { return userQuery; }
    public synchronized AgentTaskState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public synchronized Instant getUpdatedAt() { return updatedAt; }
    public synchronized AgentPlan getPlan() { return plan; }
    public synchronized List<ToolExecutionRecord> getToolCalls() { return List.copyOf(toolCalls); }
    public synchronized List<Evidence> getEvidence() { return List.copyOf(evidence); }
    public synchronized ApprovalDecision getApproval() { return approval; }
    public synchronized AgentResult getResult() { return result; }
    public synchronized String getError() { return error; }
    public String getOwnerId() { return ownerId; }
    public String getTenantId() { return tenantId; }
    public synchronized long getVersion() { return version; }
    public synchronized String getPlanHash() { return planHash; }
    public synchronized String getApprovedPlanHash() { return approvedPlanHash; }
    public synchronized Instant getApprovalValidUntil() { return approvalValidUntil; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(taskId, userQuery, ownerId, tenantId, state, createdAt, updatedAt, plan, List.copyOf(toolCalls),
                List.copyOf(evidence), approval, result, error, planHash, approvedPlanHash, approvalValidUntil, Map.copyOf(completedSteps));
    }
    public static AgentTask from(Snapshot s) {
        AgentTask task = new AgentTask(s.taskId(), s.userQuery());
        task.ownerId = s.ownerId(); task.tenantId = s.tenantId(); task.state = s.state();
        task.createdAt = s.createdAt(); task.updatedAt = s.updatedAt(); task.plan = s.plan();
        task.toolCalls.addAll(s.toolCalls()); task.evidence.addAll(s.evidence()); task.approval = s.approval();
        task.result = s.result(); task.error = s.error(); task.planHash = s.planHash();
        task.approvedPlanHash = s.approvedPlanHash(); task.approvalValidUntil = s.approvalValidUntil();
        task.completedSteps.putAll(s.completedSteps());
        return task;
    }
    // 评测调用方持有的是快照，显式刷新以兼容多进程更新，而不是依赖 Java 对象共享。
    public synchronized void refreshFrom(AgentTask other) {
        Snapshot s = other.snapshot();
        if (!taskId.equals(s.taskId())) throw new IllegalArgumentException("不能混用不同任务快照");
        state = s.state(); updatedAt = s.updatedAt(); plan = s.plan(); approval = s.approval(); result = s.result(); error = s.error();
        toolCalls.clear(); toolCalls.addAll(s.toolCalls()); evidence.clear(); evidence.addAll(s.evidence());
        completedSteps.clear(); completedSteps.putAll(s.completedSteps()); version = other.getVersion();
        planHash = s.planHash(); approvedPlanHash = s.approvedPlanHash(); approvalValidUntil = s.approvalValidUntil();
    }
    public record Snapshot(String taskId, String userQuery, String ownerId, String tenantId, AgentTaskState state,
                           Instant createdAt, Instant updatedAt, AgentPlan plan, List<ToolExecutionRecord> toolCalls,
                           List<Evidence> evidence, ApprovalDecision approval, AgentResult result, String error,
                           String planHash, String approvedPlanHash, Instant approvalValidUntil,
                           Map<String, ToolExecutionRecord> completedSteps) {}
}
