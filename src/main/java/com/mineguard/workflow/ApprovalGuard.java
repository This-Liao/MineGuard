package com.mineguard.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.approval.ApprovalStatus;
import com.mineguard.security.*;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** 在每个高风险写步骤发送前重新检查审批摘要、期限与审批人的当前权限。 */
@Component
public class ApprovalGuard {
    private final JdbcAgentTaskStore store;
    private final ObjectMapper mapper;
    private final IdentityService identities;
    private final TaskAccessPolicy policy;

    public ApprovalGuard(JdbcAgentTaskStore store, ObjectMapper mapper, IdentityService identities, TaskAccessPolicy policy) {
        this.store = store; this.mapper = mapper; this.identities = identities; this.policy = policy;
    }

    public void requireValid(AgentTask task, boolean approved) {
        if (!approved || task.getApproval() == null || task.getApproval().status() != ApprovalStatus.APPROVED
                || !Objects.equals(task.getApprovedPlanHash(), Digests.canonical(mapper, task.getPlan()))
                || task.getApprovalValidUntil() == null || !task.getApprovalValidUntil().isAfter(store.now())) {
            throw new IllegalStateException("审批缺失、过期或计划参数发生变化");
        }
        if (!"evaluation".equals(task.getTenantId())) {
            Actor approver = identities.enabledUser(task.getApproval().decidedBy())
                    .orElseThrow(() -> new IllegalStateException("审批人已停用"));
            policy.requireApproval(approver, task);
        }
    }
}
