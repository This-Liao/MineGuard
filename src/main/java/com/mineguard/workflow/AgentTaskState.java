package com.mineguard.workflow;

import java.util.EnumSet;
import java.util.Set;

public enum AgentTaskState {
    CREATED,
    PLANNING,
    RETRIEVING,
    ANALYZING,
    WAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(AgentTaskState next) {
        return allowed().contains(next);
    }

    private Set<AgentTaskState> allowed() {
        return switch (this) {
            case CREATED -> EnumSet.of(PLANNING, FAILED);
            case PLANNING -> EnumSet.of(RETRIEVING, FAILED);
            case RETRIEVING -> EnumSet.of(ANALYZING, FAILED);
            case ANALYZING -> EnumSet.of(WAITING_APPROVAL, COMPLETED, FAILED);
            case WAITING_APPROVAL -> EnumSet.of(EXECUTING, COMPLETED, FAILED);
            case EXECUTING -> EnumSet.of(VERIFYING, FAILED);
            case VERIFYING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(AgentTaskState.class);
        };
    }

    public boolean terminal() { return this == COMPLETED || this == FAILED; }
}
