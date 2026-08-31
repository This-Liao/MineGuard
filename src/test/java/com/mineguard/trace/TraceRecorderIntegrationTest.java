package com.mineguard.trace;

import com.mineguard.workflow.AgentTask;
import com.mineguard.workflow.AgentTaskState;
import com.mineguard.workflow.AgentWorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TraceRecorderIntegrationTest {
    @Autowired AgentWorkflowEngine workflow;
    @Autowired TraceRecorder traces;

    @Test
    void recordsObservableWorkflowWithoutHiddenReasoning() throws Exception {
        AgentTask task = workflow.create("查询安全帽 PPE 检查规范");
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!task.getState().terminal() && System.nanoTime() < deadline) { task.refreshFrom(workflow.get(task.getTaskId())); Thread.sleep(10); }
        assertThat(task.getState()).isEqualTo(AgentTaskState.COMPLETED);
        TraceRecorder.TraceRunView trace = traces.get(task.getTaskId()).orElseThrow();
        assertThat(trace.stateTransitions()).isNotEmpty();
        assertThat(trace.toolCalls()).isNotEmpty();
        assertThat(trace.retrieval()).isNotEmpty();
        assertThat(trace.result()).isNotNull();
        assertThat(trace.events()).allSatisfy(event ->
                assertThat(event.data().keySet()).noneMatch(key -> key.toLowerCase().contains("api-key") || key.toLowerCase().contains("cot")));
    }
}
