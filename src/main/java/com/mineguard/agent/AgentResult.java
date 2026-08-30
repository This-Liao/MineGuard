package com.mineguard.agent;

import com.mineguard.rag.Evidence;

import java.util.List;

public record AgentResult(
        String taskId,
        String summary,
        RiskLevel riskLevel,
        List<String> findings,
        List<String> actions,
        List<Evidence> evidence,
        List<String> executedOperations,
        List<String> verification,
        List<String> warnings
) {
    public AgentResult {
        findings = safe(findings);
        actions = safe(actions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        executedOperations = safe(executedOperations);
        verification = safe(verification);
        warnings = safe(warnings);
    }

    private static List<String> safe(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
