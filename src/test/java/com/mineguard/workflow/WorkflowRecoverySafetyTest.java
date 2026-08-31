package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.*;
import com.mineguard.device.*;
import com.mineguard.security.*;
import com.mineguard.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"mineguard.runtime.scheduler-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:recovery_safety;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class WorkflowRecoverySafetyTest {
    @Autowired AgentWorkflowEngine engine;
    @Autowired JdbcAgentTaskStore store;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identities;
    @MockBean IndustrialGateway gateway;

    @BeforeEach void setup() {
        when(gateway.getDeviceStatus(anyString())).thenReturn(DeviceStatus.ONLINE);
        when(gateway.listDetectionTasks(anyString())).thenReturn(List.of());
        when(gateway.verifyDetectionTask(anyString(), anyString(), anyString())).thenReturn(true);
    }
    private AgentTask prepared() {
        AgentTask task = engine.create("启动 camera-03 的 intrusion_detection 检测任务");
        engine.runLease(store.claim(task.getTaskId(), "planner", 30).orElseThrow());
        return engine.approve(task.getTaskId(), "reviewer", "故障恢复测试");
    }
    private PlanStep write(AgentTask task) { return task.getPlan().steps().stream().filter(s -> s.type() == AgentStepType.START_DETECTION_TASK).findFirst().orElseThrow(); }
    private JdbcAgentTaskStore.Lease begin(AgentTask task) {
        var lease = store.claim(task.getTaskId(), "worker", 30).orElseThrow(); PlanStep step = write(task);
        store.beginStep(task, lease, step.id(), Digests.canonical(mapper, step), true,
                new JdbcAgentTaskStore.EventDraft(TaskEventType.TOOL_STARTED, Map.of("stepId", step.id())));
        return lease;
    }
    @Test void completedReceiverReceiptRestoresWithoutResendingWrite() {
        AgentTask task = prepared(); var lease = begin(task);
        when(gateway.operationReceipt(task.getTaskId() + ":" + write(task).id())).thenReturn(Optional.of(new DetectionTask("receiver-task", "camera-03", "intrusion_detection", "RUNNING", Instant.now())));
        engine.runLease(lease);
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.COMPLETED);
        verify(gateway, never()).startDetectionTask(anyString(), anyString(), anyString());
        verify(gateway).verifyDetectionTask("camera-03", "intrusion_detection", "RUNNING");
        assertThat(store.history(task.getTaskId(), 0, 100)).anyMatch(e -> Boolean.TRUE.equals(e.payload().get("recoveredFromReceipt")));
    }
    @Test void wrongTargetReceiptCannotProveCompletion() {
        AgentTask task = prepared(); var lease = begin(task);
        when(gateway.operationReceipt(anyString())).thenReturn(Optional.of(new DetectionTask("wrong-task", "camera-08", "no_helmet", "RUNNING", Instant.now())));
        engine.runLease(lease);
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.RECOVERY_REQUIRED);
        verify(gateway, never()).startDetectionTask(anyString(), anyString(), anyString());
    }
    @Test void receiptNetworkFailureIsNotConvertedIntoPermissionToRetry() {
        AgentTask task = prepared(); var lease = begin(task);
        when(gateway.operationReceipt(anyString())).thenThrow(new IllegalStateException("服务不可达"));
        engine.runLease(lease);
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.RECOVERY_REQUIRED);
        verify(gateway, never()).startDetectionTask(anyString(), anyString(), anyString());
    }
    @Test void durableFailureRemainsFailureAfterCrashBeforeTerminalCheckpoint() { assertRecordedFailure("TOOL_EXECUTION_FAILED", AgentTaskState.FAILED); }
    @Test void durableUnknownOutcomeRemainsManualReviewAfterCrash() { assertRecordedFailure("OUTCOME_UNKNOWN", AgentTaskState.RECOVERY_REQUIRED); }
    private void assertRecordedFailure(String error, AgentTaskState expected) {
        AgentTask task = prepared(); var lease = begin(task); PlanStep step = write(task);
        var call = new ToolExecutionRecord("start_detection_task", step.args(), ToolCategory.HIGH_RISK, ToolResult.failure(error, "故障注入"), Instant.now());
        task.completeStep(step.id(), call); store.completeStep(task, lease, step.id(), call, List.of());
        engine.runLease(lease);
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(expected);
        verify(gateway, never()).startDetectionTask(anyString(), anyString(), anyString());
    }
    @Test void disabledApproverCannotAuthorizeDelayedWrite() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Actor owner = identities.createAccount("test", "owner-" + suffix, "local-test-password", Set.of(Role.OPERATOR));
        Actor reviewer = identities.createAccount("test", "reviewer-" + suffix, "local-test-password", Set.of(Role.APPROVER));
        AgentTask task = engine.create("启动 camera-03 的 intrusion_detection 检测任务", owner, suffix);
        engine.runLease(store.claim(task.getTaskId(), "planner", 30).orElseThrow()); task = engine.get(task.getTaskId());
        task = engine.decide(task.getTaskId(), reviewer, true, "独立审批", task.getPlanHash(), suffix);
        identities.setEnabled(new Actor("admin", "test", "admin", Set.of(Role.ADMIN)), reviewer.userId(), false);
        engine.runLease(store.claim(task.getTaskId(), "worker", 30).orElseThrow());
        assertThat(engine.get(task.getTaskId()).getState()).isEqualTo(AgentTaskState.FAILED);
        assertThat(engine.get(task.getTaskId()).getError()).contains("审批人已停用");
        verify(gateway, never()).startDetectionTask(anyString(), anyString(), anyString());
    }
}
