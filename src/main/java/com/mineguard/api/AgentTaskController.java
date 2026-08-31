package com.mineguard.api;

import com.mineguard.workflow.AgentTask;
import com.mineguard.workflow.AgentWorkflowEngine;
import com.mineguard.workflow.TaskEvent;
import com.mineguard.workflow.TaskEventPublisher;
import com.mineguard.agent.AgentReport;
import com.mineguard.agent.AgentReportComposer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import com.mineguard.security.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {
    private final AgentWorkflowEngine workflow;
    private final TaskEventPublisher events;
    private final IdentityService identities;
    private final TaskAccessPolicy policy;
    private final AgentReportComposer reports;

    public AgentTaskController(AgentWorkflowEngine workflow, TaskEventPublisher events, IdentityService identities, TaskAccessPolicy policy, AgentReportComposer reports) {
        this.workflow = workflow;
        this.events = events;
        this.identities = identities; this.policy = policy;
        this.reports = reports;
    }

    @PostMapping
    public AgentTask create(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateTaskRequest request) {
        return workflow.create(request.query(), actor, key);
    }

    @GetMapping
    public List<AgentTask> list(@AuthenticationPrincipal Actor actor) { return workflow.list(actor); }

    @GetMapping("/{id}")
    public AgentTask get(@PathVariable String id, @AuthenticationPrincipal Actor actor) { return workflow.get(id, actor); }

    @GetMapping("/{id}/report")
    public AgentReport report(@PathVariable String id, @AuthenticationPrincipal Actor actor) {
        // 先检查原任务访问权限，再从该任务已有数据生成只读展示，不重跑工具。
        return reports.existing(workflow.get(id, actor));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @AuthenticationPrincipal Actor actor,
                             @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long cursor,
                             @RequestHeader("Authorization") String authorization) {
        workflow.get(id, actor);
        String token = BearerTokenFilter.token(authorization);
        return events.subscribe(id, cursor, () -> identities.authenticate(token).map(current -> policy.canRead(current, workflow.get(id))).orElse(false));
    }

    @GetMapping("/{id}/events")
    public List<TaskEvent> eventHistory(@PathVariable String id, @AuthenticationPrincipal Actor actor,
                                        @RequestParam(defaultValue = "0") long after) {
        workflow.get(id, actor);
        if (after < 0) throw new IllegalArgumentException("事件游标不能为负数");
        return events.history(id, after);
    }

    public record CreateTaskRequest(@NotBlank String query) {}
}
