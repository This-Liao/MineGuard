package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.MineGuardApplication;
import com.mineguard.device.IndustrialGateway;
import com.mineguard.device.MockIndustrialGateway;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.llm.DeterministicAgentModelClient;
import com.mineguard.llm.OpenAiCompatibleAgentModelClient;
import com.mineguard.rag.InMemoryVectorStore;
import com.mineguard.rag.VectorStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealModelEvalApplicationTest {
    @TempDir Path output;

    @Test void isolatedPipelineUsesFakeHttpAndWritesSeparateChineseReport() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var deterministic = new DeterministicAgentModelClient(mapper);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            var user = mapper.readTree(request.path("messages").path(1).path("content").asText());
            String content = deterministic.createPlan(user.path("query").asText(), List.of(), null);
            byte[] bytes = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        byte[] deterministicReport = Files.readAllBytes(Path.of("docs/eval/latest.json"));
        var args = new ArrayList<>(List.of(RealModelEvalApplication.isolationArguments(output)));
        args.addAll(List.of("--mineguard.llm.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--mineguard.llm.api-key=offline-test-secret", "--mineguard.llm.model=deepseek-v4-flash",
                "--mineguard.llm.max-calls=5", "--mineguard.llm.max-output-tokens=2048", "--mineguard.llm.request-timeout-seconds=3"));
        try (var context = new SpringApplicationBuilder(MineGuardApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url=jdbc:postgresql://unreachable/production", "mineguard.vector-store.type=milvus")
                .run(args.toArray(String[]::new))) {
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:mineguard_real_eval");
            }
            assertThat(context.getBean(VectorStore.class)).isInstanceOf(InMemoryVectorStore.class);
            assertThat(context.getBean(IndustrialGateway.class)).isInstanceOf(MockIndustrialGateway.class);
            var agent = context.getBean(AgentEvaluator.class).evaluate(Path.of("data/eval/agent_cases.json"), 3, Duration.ofSeconds(5));
            var safety = context.getBean(SafetyEvaluator.class).evaluate(Path.of("data/eval/safety_cases.json"), 2, Duration.ofSeconds(5));
            assertThat(agent.caseCount()).isEqualTo(3);
            assertThat(agent.taskSuccessRate()).isEqualTo(1);
            assertThat(safety.approvalEnforcedCount()).isEqualTo(2);
            var usage = ((OpenAiCompatibleAgentModelClient) context.getBean(AgentModelClient.class)).usageSnapshot();
            assertThat(usage.attempts()).isEqualTo(5);
            assertThat(usage.recordedTotalTokens()).isEqualTo(75);
            RealModelEvalApplication.writeReport(output, Map.of("status", "COMPLETED", "agent", agent, "safety", safety, "usage", usage), mapper);
            assertThat(Files.readString(output.resolve("REPORT.md"), StandardCharsets.UTF_8)).contains("真实模型评测记录", "不是零", "小样本");
            assertThat(Files.readString(output.resolve("report.json"))).doesNotContain("offline-test-secret");
            assertThat(Files.readAllBytes(Path.of("docs/eval/latest.json"))).isEqualTo(deterministicReport);
        } finally { server.stop(0); }
    }

    @Test void deterministicEntryRejectsRealModelsBeforeRunningEvaluators() {
        var model = mock(AgentModelClient.class);
        when(model.realModel()).thenReturn(true);
        var agent = mock(AgentEvaluator.class);
        var orchestrator = new EvaluationOrchestrator(null, agent, null, null, null, model, new ObjectMapper());
        assertThatThrownBy(orchestrator::runAll).hasMessageContaining("run-real-eval.ps1");
        verifyNoInteractions(agent);
    }

    @Test void reportCanRepresentAbortedRunWithNoUsage() throws Exception {
        RealModelEvalApplication.writeReport(output, Map.of("status", "ABORTED"), new ObjectMapper());
        assertThat(Files.readString(output.resolve("REPORT.md"))).contains("ABORTED", "尚未取得模型用量");
    }
}
