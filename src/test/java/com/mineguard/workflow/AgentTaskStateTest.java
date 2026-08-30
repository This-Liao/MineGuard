package com.mineguard.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentTaskStateTest {
    @Test
    void acceptsReadOnlyHappyPath() {
        AgentTask task = new AgentTask("t-1", "query");
        task.transitionTo(AgentTaskState.PLANNING);
        task.transitionTo(AgentTaskState.RETRIEVING);
        task.transitionTo(AgentTaskState.ANALYZING);
        task.transitionTo(AgentTaskState.COMPLETED);
        assertThat(task.getState()).isEqualTo(AgentTaskState.COMPLETED);
    }

    @Test
    void acceptsApprovedExecutionPath() {
        AgentTask task = new AgentTask("t-2", "start camera");
        task.transitionTo(AgentTaskState.PLANNING);
        task.transitionTo(AgentTaskState.RETRIEVING);
        task.transitionTo(AgentTaskState.ANALYZING);
        task.transitionTo(AgentTaskState.WAITING_APPROVAL);
        task.transitionTo(AgentTaskState.EXECUTING);
        task.transitionTo(AgentTaskState.VERIFYING);
        task.transitionTo(AgentTaskState.COMPLETED);
        assertThat(task.getState()).isEqualTo(AgentTaskState.COMPLETED);
    }

    @Test
    void rejectsIllegalTransition() {
        AgentTask task = new AgentTask("t-3", "query");
        assertThatThrownBy(() -> task.transitionTo(AgentTaskState.EXECUTING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATED -> EXECUTING");
    }

    @Test
    void terminalStateCannotRestart() {
        AgentTask task = new AgentTask("t-4", "query");
        task.transitionTo(AgentTaskState.FAILED);
        assertThatThrownBy(() -> task.transitionTo(AgentTaskState.PLANNING)).isInstanceOf(IllegalStateException.class);
    }
}
