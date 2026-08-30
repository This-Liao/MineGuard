package com.mineguard.api;

import com.mineguard.workflow.AgentTask;
import com.mineguard.workflow.AgentWorkflowEngine;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class ApprovalController {
    private final AgentWorkflowEngine workflow;
    public ApprovalController(AgentWorkflowEngine workflow) { this.workflow = workflow; }

    @PostMapping("/{id}/approve")
    public AgentTask approve(@PathVariable String id, @RequestBody(required = false) ApprovalRequest request) {
        ApprovalRequest safe = request == null ? new ApprovalRequest(null, null) : request;
        return workflow.approve(id, safe.actor(), safe.reason());
    }

    @PostMapping("/{id}/reject")
    public AgentTask reject(@PathVariable String id, @RequestBody(required = false) ApprovalRequest request) {
        ApprovalRequest safe = request == null ? new ApprovalRequest(null, null) : request;
        return workflow.reject(id, safe.actor(), safe.reason());
    }

    public record ApprovalRequest(String actor, String reason) {}
}
