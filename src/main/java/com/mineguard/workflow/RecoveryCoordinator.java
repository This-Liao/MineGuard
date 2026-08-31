package com.mineguard.workflow;

import com.mineguard.agent.*;
import com.mineguard.device.DetectionTask;
import com.mineguard.device.IndustrialGateway;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.mineguard.workflow.JdbcAgentTaskStore.*;

/** 只核验既有写请求的回执并补记检查点，绝不发送第二次工业写请求。 */
@Component
public class RecoveryCoordinator {
    private final JdbcAgentTaskStore store;
    private final IndustrialGateway gateway;

    public RecoveryCoordinator(JdbcAgentTaskStore store, IndustrialGateway gateway) {
        this.store = store; this.gateway = gateway;
    }

    public void recover(AgentTask task, Lease lease, PlanStep step, Tool tool, Step existing) {
        Optional<DetectionTask> receipt;
        try { receipt = gateway.operationReceipt(existing.operationKey()); }
        catch (Exception ex) { throw new UnknownOutcome("外部回执查询不可用，保留待核验状态，禁止重放写请求"); }
        String expected = step.type() == AgentStepType.START_DETECTION_TASK ? "RUNNING" : "STOPPED";
        if (receipt.isPresent() && receipt.get().cameraId().equals(step.args().get("cameraId"))
                && receipt.get().algorithm().equals(step.args().get("algorithm")) && receipt.get().status().equals(expected)) {
            var record = new ToolExecutionRecord(tool.name(), step.args(), tool.category(), ToolResult.success(receipt.get()), Instant.now());
            task.completeStep(step.id(), record);
            store.completeStep(task, lease, step.id(), record, List.of(new EventDraft(TaskEventType.TOOL_FINISHED,
                    Map.of("stepId", step.id(), "tool", tool.name(), "result", record.result(), "recoveredFromReceipt", true))));
            return;
        }
        throw new UnknownOutcome("高风险步骤已发出但没有完整检查点，必须核验外部回执；禁止自动重放");
    }

    static final class UnknownOutcome extends IllegalStateException {
        UnknownOutcome(String message) { super(message); }
    }
}
