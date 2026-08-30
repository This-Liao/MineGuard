package com.mineguard.workflow;

import java.util.List;
import java.util.Optional;

public interface AgentTaskStore {
    AgentTask save(AgentTask task);
    Optional<AgentTask> findById(String taskId);
    List<AgentTask> findAll();
}
