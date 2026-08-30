package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.agent.PlanStep;
import com.mineguard.agent.RiskLevel;
import com.mineguard.tool.ToolCategory;
import com.mineguard.tool.ToolRegistry;
import com.mineguard.workflow.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Component
public class AgentEvaluator {
    private final AgentWorkflowEngine workflow;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public AgentEvaluator(AgentWorkflowEngine workflow, ToolRegistry registry, ObjectMapper mapper) {
        this.workflow = workflow;
        this.registry = registry;
        this.mapper = mapper;
    }

    public Result evaluate(Path casesPath) {
        try {
            List<Case> cases = mapper.readValue(casesPath.toFile(), new TypeReference<>() {});
            int successful = 0, toolSelections = 0, paramValidCases = 0, paramEligible = 0;
            int approvalEnforced = 0, approvalCases = 0, evidenceCovered = 0, evidenceCases = 0;
            long toolCalls = 0;
            List<Long> latencies = new ArrayList<>();
            List<CaseResult> details = new ArrayList<>();
            for (Case testCase : cases) {
                long started = System.nanoTime();
                AgentTask task = workflow.create(testCase.query());
                boolean enforced = false;
                if (testCase.approvalRequired()) {
                    approvalCases++;
                    awaitOneOf(task, Set.of(AgentTaskState.WAITING_APPROVAL, AgentTaskState.FAILED), Duration.ofSeconds(8));
                    if (task.getState() == AgentTaskState.WAITING_APPROVAL) {
                        enforced = task.getToolCalls().stream().noneMatch(call -> call.category() == ToolCategory.HIGH_RISK && call.result().success());
                        if (enforced) approvalEnforced++;
                        workflow.approve(task.getTaskId(), "eval-runner", testCase.id());
                    }
                }
                awaitTerminal(task, Duration.ofSeconds(8));
                long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
                latencies.add(latency);
                List<String> actualTools = task.getPlan() == null ? List.of()
                        : task.getPlan().steps().stream().map(PlanStep::type).map(type -> type.toolName()).toList();
                boolean selectionCorrect = new LinkedHashSet<>(actualTools).equals(new LinkedHashSet<>(testCase.expectedTools()));
                if (selectionCorrect) toolSelections++;
                boolean paramsValid = false;
                if (task.getPlan() != null) {
                    paramEligible++;
                    paramsValid = task.getPlan().steps().stream().allMatch(step ->
                            registry.get(step.type().toolName()).schema().validate(step.args()).isEmpty());
                    if (paramsValid) paramValidCases++;
                }
                boolean outcomeCorrect = task.getState().name().equals(testCase.expectedOutcome());
                boolean riskCorrect = task.getPlan() == null
                        ? "FAILED".equals(testCase.expectedOutcome())
                        : task.getPlan().riskLevel() == testCase.expectedRisk();
                boolean caseSuccess = outcomeCorrect && riskCorrect && selectionCorrect
                        && (!testCase.approvalRequired() || enforced);
                if (caseSuccess) successful++;
                toolCalls += task.getToolCalls().size();
                boolean expectsEvidence = testCase.expectedTools().contains("search_safety_knowledge");
                if (expectsEvidence) {
                    evidenceCases++;
                    if (!task.getEvidence().isEmpty()) evidenceCovered++;
                }
                details.add(new CaseResult(testCase.id(), testCase.category(), task.getState(), caseSuccess,
                        selectionCorrect, paramsValid, enforced, latency, actualTools, task.getError()));
            }
            latencies.sort(Long::compareTo);
            int count = cases.size();
            return new Result(count, rate(successful, count), rate(toolSelections, count), rate(paramValidCases, paramEligible),
                    rate(approvalEnforced, approvalCases), round(count == 0 ? 0 : (double) toolCalls / count),
                    percentile(latencies, 0.50), percentile(latencies, 0.95), rate(evidenceCovered, evidenceCases), details);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read agent cases: " + casesPath, ex);
        }
    }

    private void awaitTerminal(AgentTask task, Duration timeout) {
        awaitOneOf(task, Set.of(AgentTaskState.COMPLETED, AgentTaskState.FAILED), timeout);
    }

    private void awaitOneOf(AgentTask task, Set<AgentTaskState> expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!expected.contains(task.getState()) && System.nanoTime() < deadline) {
            try { Thread.sleep(2); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("evaluation interrupted", ex); }
        }
        if (!expected.contains(task.getState())) throw new IllegalStateException("task timed out in " + task.getState());
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * values.size()) - 1);
        return values.get(Math.min(index, values.size() - 1));
    }
    private double rate(int value, int count) { return round(count == 0 ? 0 : (double) value / count); }
    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }

    public record Case(String id, String category, String query, List<String> expectedTools,
                       RiskLevel expectedRisk, boolean approvalRequired, String expectedOutcome) {}
    public record CaseResult(String id, String category, AgentTaskState state, boolean success,
                             boolean toolSelectionCorrect, boolean parametersValid, boolean approvalEnforced,
                             long latencyMs, List<String> actualTools, String error) {}
    public record Result(int caseCount, double taskSuccessRate, double toolSelectionAccuracy,
                         double toolParameterValidRate, double approvalEnforcementRate, double averageToolCalls,
                         long p50LatencyMs, long p95LatencyMs, double ragEvidenceCoverage, List<CaseResult> cases) {}
}
