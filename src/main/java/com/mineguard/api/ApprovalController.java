package com.mineguard.api;

import com.mineguard.workflow.AgentTask;
import com.mineguard.workflow.AgentWorkflowEngine;
import org.springframework.web.bind.annotation.*;
import com.mineguard.security.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/tasks")
public class ApprovalController {
    private final AgentWorkflowEngine workflow;
    public ApprovalController(AgentWorkflowEngine workflow) { this.workflow = workflow; }

    @PostMapping("/{id}/approve")
    public AgentTask approve(@PathVariable String id, @AuthenticationPrincipal Actor actor,
                             @RequestHeader("Idempotency-Key") String key, @RequestBody ApprovalRequest request) {
        return workflow.decide(id, actor, true, request.reason(), request.planHash(), key);
    }

    @PostMapping("/{id}/reject")
    public AgentTask reject(@PathVariable String id, @AuthenticationPrincipal Actor actor,
                            @RequestHeader("Idempotency-Key") String key, @RequestBody ApprovalRequest request) {
        return workflow.decide(id, actor, false, request.reason(), request.planHash(), key);
    }

    public record ApprovalRequest(String reason, String planHash) {}
}
