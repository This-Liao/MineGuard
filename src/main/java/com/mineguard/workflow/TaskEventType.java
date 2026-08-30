package com.mineguard.workflow;

public enum TaskEventType {
    TASK_STATE_CHANGED,
    PLAN_CREATED,
    TOOL_STARTED,
    TOOL_FINISHED,
    RAG_RETRIEVED,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    VERIFICATION,
    FINAL_RESULT,
    ERROR
}
