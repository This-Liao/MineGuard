package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.MineGuardApplication;
import com.mineguard.llm.ModelUsageRecorder;
import com.mineguard.tool.ToolRegistry;
import com.mineguard.workflow.AgentWorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jRegressionApplicationTest {
    @Test void frozenManifestMatchesCurrentRegressionSources() throws Exception {
        var manifest = LangChain4jRegressionGuard.verify(Path.of(""), new ObjectMapper());
        assertThat(manifest.path("caseCount").asInt()).isEqualTo(24);
        assertThat(manifest.path("maxCalls").asInt()).isEqualTo(48);
        assertThat(manifest.path("developerVisible").asBoolean()).isTrue();
    }

    @Test void isolatedArgumentsSelectAiServicesAndSafeDependencies() {
        assertThat(LangChain4jRegressionApplication.isolationArguments(Path.of("target/regression")))
                .contains("--mineguard.llm.provider=langchain4j-openai-compatible",
                        "--mineguard.vector-store.type=in-memory",
                        "--mineguard.embedding.provider=hashing",
                        "--mineguard.industrial.type=mock");
    }

    @Test void adapterMetricsSeparateTransportStructuredOutputAndRepair() {
        var recorder = new ModelUsageRecorder(4);
        recorder.complete(recorder.reserve(), Instant.parse("2026-09-13T00:00:00Z"), 100, 200, "SUCCESS",
                new ModelUsageRecorder.Tokens(10L, 5L, 15L, null, null, null));
        recorder.complete(recorder.reserve(), Instant.parse("2026-09-13T00:00:01Z"), 300, 200,
                "STRUCTURED_OUTPUT_ERROR", ModelUsageRecorder.Tokens.unknown());
        recorder.complete(recorder.reserve(), Instant.parse("2026-09-13T00:00:02Z"), 200, 200, "SUCCESS",
                new ModelUsageRecorder.Tokens(20L, 5L, 25L, null, null, null));

        var metrics = LangChain4jRegressionApplication.usageMetrics(recorder.snapshot(), 2);
        assertThat(metrics.requestCount()).isEqualTo(3);
        assertThat(metrics.http200ResponseRate()).isEqualTo(1);
        assertThat(metrics.typedStructuredSuccessRate()).isEqualTo(0.6667);
        assertThat(metrics.repairRequestCount()).isEqualTo(1);
        assertThat(metrics.usageReceiptRate()).isEqualTo(0.6667);
        assertThat(metrics.p50RequestLatencyMs()).isEqualTo(200);
        assertThat(metrics.p95RequestLatencyMs()).isEqualTo(300);
    }

    @Test void dedicatedScorerCoversOutcomeRiskToolsRejectionAndApprovalOffline() {
        String[] args = {"--spring.profiles.active=test", "--mineguard.llm.provider=deterministic",
                "--spring.datasource.url=jdbc:h2:mem:langchain4j_regression_scorer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--mineguard.vector-store.type=in-memory", "--mineguard.embedding.provider=hashing",
                "--mineguard.retrieval.mode=vector", "--mineguard.knowledge-path=data/knowledge",
                "--mineguard.industrial.type=mock", "--mineguard.runtime.scheduler-enabled=true",
                "--mineguard.demo-data-enabled=true", "--mineguard.trace-path=target/langchain4j-regression-scorer-traces"};
        try (var context = new SpringApplicationBuilder(MineGuardApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            var evaluator = new LangChain4jRegressionEvaluator(context.getBean(AgentWorkflowEngine.class),
                    context.getBean(ToolRegistry.class), context.getBean(ObjectMapper.class));
            var result = evaluator.evaluate(Path.of("data/eval/agent_cases.json"), 30, Duration.ofSeconds(8));
            assertThat(result.caseCount()).isEqualTo(30);
            assertThat(result.taskSuccessRate()).isEqualTo(1);
            assertThat(result.outcomeAccuracy()).isEqualTo(1);
            assertThat(result.riskAccuracy()).isEqualTo(1);
            assertThat(result.toolSelectionAccuracy()).isEqualTo(1);
            assertThat(result.toolParameterValidRate()).isEqualTo(1);
            assertThat(result.approvalEnforcementRate()).isEqualTo(1);
            assertThat(result.executablePlanAcceptanceRate()).isEqualTo(1);
            assertThat(result.correctRejectionRate()).isEqualTo(1);
            assertThat(result.ragEvidenceCoverage()).isEqualTo(1);
        }
    }
}
