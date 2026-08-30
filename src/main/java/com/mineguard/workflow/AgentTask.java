package com.mineguard.workflow;

import com.mineguard.agent.AgentPlan;
import com.mineguard.agent.AgentResult;
import com.mineguard.approval.ApprovalDecision;
import com.mineguard.rag.Evidence;
import com.mineguard.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AgentTask {
    private final String taskId;
    private final String userQuery;
    private AgentTaskState state = AgentTaskState.CREATED;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;
    private AgentPlan plan;
    private final List<ToolExecutionRecord> toolCalls = new ArrayList<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private ApprovalDecision approval;
    private AgentResult result;
    private String error;

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
}
