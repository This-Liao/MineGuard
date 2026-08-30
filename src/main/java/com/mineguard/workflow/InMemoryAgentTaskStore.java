package com.mineguard.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryAgentTaskStore implements AgentTaskStore {
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    @Override public AgentTask save(AgentTask task) { tasks.put(task.getTaskId(), task); return task; }
    @Override public Optional<AgentTask> findById(String taskId) { return Optional.ofNullable(tasks.get(taskId)); }
    @Override public List<AgentTask> findAll() {
        return tasks.values().stream().sorted(java.util.Comparator.comparing(AgentTask::getCreatedAt).reversed()).toList();
    }
}
