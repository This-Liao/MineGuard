package com.mineguard.approval;

import java.time.Instant;

public record ApprovalDecision(ApprovalStatus status, String decidedBy, String reason, Instant decidedAt) {
    public static ApprovalDecision pending() {
        return new ApprovalDecision(ApprovalStatus.PENDING, null, null, null);
    }
    public static ApprovalDecision approved(String actor, String reason) {
        return new ApprovalDecision(ApprovalStatus.APPROVED, actor, reason, Instant.now());
    }
    public static ApprovalDecision rejected(String actor, String reason) {
        return new ApprovalDecision(ApprovalStatus.REJECTED, actor, reason, Instant.now());
    }
}
