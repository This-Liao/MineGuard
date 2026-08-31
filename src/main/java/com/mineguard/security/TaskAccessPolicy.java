package com.mineguard.security;

import com.mineguard.workflow.AgentTask;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

@Component
public class TaskAccessPolicy {
    public void requireRole(Actor actor, Role role) {
        if (actor == null || !actor.has(role)) throw new AccessDeniedException("当前身份没有此操作权限");
    }
    public boolean canRead(Actor actor, AgentTask task) {
        return actor != null && actor.tenantId().equals(task.getTenantId()) && (actor.userId().equals(task.getOwnerId())
                || actor.has(Role.OBSERVER) || actor.has(Role.APPROVER) || actor.has(Role.ADMIN));
    }
    public void requireRead(Actor actor, AgentTask task) {
        if (!canRead(actor, task)) throw new NoSuchElementException("任务不存在或不可访问");
    }
    public void requireApproval(Actor actor, AgentTask task) {
        requireRead(actor, task); requireRole(actor, Role.APPROVER);
        if (actor.userId().equals(task.getOwnerId())) throw new AccessDeniedException("禁止审批自己发起的任务");
    }
}
