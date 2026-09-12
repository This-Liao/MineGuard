package com.mineguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.MineGuardApplication;
import com.mineguard.agent.PlanningContract;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.device.IndustrialGateway;
import com.mineguard.device.MockIndustrialGateway;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.llm.LangChain4jAgentModelClient;
import com.mineguard.llm.ModelUsageRecorder;
import com.mineguard.security.Digests;
import com.mineguard.tool.ToolRegistry;
import com.mineguard.workflow.AgentWorkflowEngine;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 对已公开的 24 条 Agent 留出题执行一次真实 AI Services 适配器一致性回归。 */
public final class LangChain4jRegressionApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private LangChain4jRegressionApplication() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("此入口不接受 Spring 覆盖参数，请使用专用回归脚本");
        JsonNode manifest = LangChain4jRegressionGuard.verify(Path.of(""), MAPPER);
        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        Path output = Path.of("data/runtime/langchain4j-regression-v2", runId).toAbsolutePath();
        Files.createDirectories(output);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("status", "ABORTED");
        report.put("startedAt", Instant.now());
        report.put("evaluationType", "LangChain4j AI Services 真实适配器一致性回归");
        report.put("interpretation", "复用开发者已见的冻结题集，不是新增盲测；单轮运行，不因得分重复调用");
        report.put("manifest", manifest);
        report.put("manifestSha256", HoldoutGuard.textHash(LangChain4jRegressionGuard.MANIFEST));
        report.put("caseSha256", HoldoutGuard.textHash(LangChain4jRegressionGuard.CASES));
        report.put("planningContract", PlanningContract.VERSION);
        report.put("promptSha256", Digests.sha256(PlanningContract.SYSTEM_PROMPT));
        report.put("executionBoundary", "真实 DeepSeek 模型请求；隔离数据库、合成业务数据和受控工业网关");

        LangChain4jAgentModelClient model = null;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MineGuardApplication.class)
                .web(WebApplicationType.NONE).run(isolationArguments(output))) {
            if (!(context.getBean(IndustrialGateway.class) instanceof MockIndustrialGateway)) {
                throw new IllegalStateException("专项回归禁止连接真实工业写网关");
            }
            AgentModelClient configured = context.getBean(AgentModelClient.class);
            if (!(configured instanceof LangChain4jAgentModelClient langChain4j)) {
                throw new IllegalStateException("专项回归未启用 LangChain4j AI Services");
            }
            model = langChain4j;
            MineGuardProperties.Llm config = context.getBean(MineGuardProperties.class).llm();
            LangChain4jRegressionGuard.requireFrozenModel(config, manifest);
            LangChain4jRegressionGuard.claimAttempt(Path.of(""), runId);

            report.put("provider", model.providerName());
            report.put("baseUrl", publicOrigin(config.baseUrl()));
            report.put("model", config.model());
            report.put("temperature", 0.0);
            report.put("maxOutputTokens", config.maxOutputTokens());
            report.put("requestTimeoutSeconds", config.requestTimeoutSeconds());
            report.put("thinking", config.thinking());
            report.put("maxCalls", config.maxCalls());

            Duration timeout = Duration.ofSeconds(2L * config.requestTimeoutSeconds() + 30);
            var evaluator = new LangChain4jRegressionEvaluator(context.getBean(AgentWorkflowEngine.class),
                    context.getBean(ToolRegistry.class), context.getBean(ObjectMapper.class));
            LangChain4jRegressionEvaluator.Result agent = evaluator.evaluate(
                    LangChain4jRegressionGuard.CASES, manifest.path("caseCount").asInt(), timeout);
            ModelUsageRecorder.Snapshot usage = model.usageSnapshot();
            AdapterMetrics adapter = usageMetrics(usage, agent.caseCount());
            report.put("agent", agent);
            report.put("adapter", adapter);
            report.put("usage", usage);
            report.put("validModelEvaluation", adapter.http200Responses() > 0);
            report.put("status", adapter.http200Responses() == 0 ? "INFRASTRUCTURE_FAILURE"
                    : usage.rejectedByBudget() == 0 && usage.pendingAttempts() == 0 ? "COMPLETED" : "INCOMPLETE");
        } catch (Exception ex) {
            report.put("failureType", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            if (model != null && !report.containsKey("usage")) {
                ModelUsageRecorder.Snapshot usage = model.usageSnapshot();
                report.put("adapter", usageMetrics(usage, 24));
                report.put("usage", usage);
            }
            report.put("finishedAt", Instant.now());
            writeReport(output, report);
            System.out.println("LangChain4j 专项回归状态：" + report.get("status") + "；报告目录：" + output);
        }
        if (!"COMPLETED".equals(report.get("status"))) {
            throw new IllegalStateException("LangChain4j 专项回归未完整结束；不得自动重跑");
        }
    }

    static String[] isolationArguments(Path output) {
        return new String[]{"--spring.profiles.active=real-eval", "--mineguard.llm.provider=langchain4j-openai-compatible",
                "--spring.datasource.url=jdbc:h2:mem:mineguard_langchain4j_regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--mineguard.vector-store.type=in-memory", "--mineguard.embedding.provider=hashing",
                "--mineguard.retrieval.mode=hybrid", "--mineguard.knowledge-path=data/knowledge",
                "--mineguard.industrial.type=mock", "--mineguard.runtime.scheduler-enabled=true",
                "--mineguard.demo-data-enabled=true", "--mineguard.trace-path=" + output.resolve("traces")};
    }

    static AdapterMetrics usageMetrics(ModelUsageRecorder.Snapshot usage, int caseCount) {
        int http200 = (int) usage.calls().stream().filter(call -> call.httpStatus() == 200).count();
        List<Long> latencies = usage.calls().stream().map(ModelUsageRecorder.Call::latencyMs).sorted().toList();
        return new AdapterMetrics(usage.attempts(), http200, rate(http200, usage.attempts()),
                usage.successfulResponses(), rate(usage.successfulResponses(), usage.attempts()),
                Math.max(0, usage.attempts() - caseCount), usage.requestsWithUsage(),
                rate(usage.requestsWithUsage(), usage.attempts()), percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    private static double rate(int value, int count) {
        return Math.round((count == 0 ? 0 : (double) value / count) * 10_000d) / 10_000d;
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(p * values.size()) - 1);
        return values.get(Math.min(index, values.size() - 1));
    }

    private static String publicOrigin(String raw) {
        URI uri = URI.create(raw);
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static void writeReport(Path output, Map<String, Object> report) throws Exception {
        var writer = MAPPER.writerWithDefaultPrettyPrinter();
        writer.writeValue(output.resolve("report.json").toFile(), report);
        if ("COMPLETED".equals(report.get("status"))) {
            Path archive = Path.of("docs/eval/langchain4j-regression-v2/report.json");
            Files.createDirectories(archive.getParent());
            writer.writeValue(archive.toFile(), report);
        }
    }

    public record AdapterMetrics(int requestCount, int http200Responses, double http200ResponseRate,
                                 int typedStructuredSuccesses, double typedStructuredSuccessRate,
                                 int repairRequestCount, int requestsWithUsage, double usageReceiptRate,
                                 long p50RequestLatencyMs, long p95RequestLatencyMs) {}
}
