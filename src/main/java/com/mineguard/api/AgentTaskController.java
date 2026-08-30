package com.mineguard.api;

import com.mineguard.workflow.AgentTask;
import com.mineguard.workflow.AgentWorkflowEngine;
import com.mineguard.workflow.TaskEvent;
import com.mineguard.workflow.TaskEventPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {
    private final AgentWorkflowEngine workflow;
    private final TaskEventPublisher events;

    public AgentTaskController(AgentWorkflowEngine workflow, TaskEventPublisher events) {
        this.workflow = workflow;
        this.events = events;
    }

    @PostMapping
    public AgentTask create(@Valid @RequestBody CreateTaskRequest request) {
        return workflow.create(request.query());
    }

    @GetMapping
    public List<AgentTask> list() { return workflow.list(); }

    @GetMapping("/{id}")
    public AgentTask get(@PathVariable String id) { return workflow.get(id); }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        workflow.get(id);
        return events.subscribe(id);
    }

    @GetMapping("/{id}/events")
    public List<TaskEvent> eventHistory(@PathVariable String id) {
        workflow.get(id);
        return events.history(id);
    }

    public record CreateTaskRequest(@NotBlank String query) {}
}
