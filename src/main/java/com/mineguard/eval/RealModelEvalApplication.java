package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.MineGuardApplication;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.agent.PlanningContract;
import com.mineguard.security.Digests;
import com.mineguard.device.IndustrialGateway;
import com.mineguard.device.MockIndustrialGateway;
import com.mineguard.llm.AgentModelClient;
import com.mineguard.llm.ModelUsageRecorder;
import com.mineguard.llm.OpenAiCompatibleAgentModelClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 有调用上限的真实模型评测，工具只在合成数据和模拟工业网关中执行。 */
public final class RealModelEvalApplication {
    private RealModelEvalApplication() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("此入口不接受 Spring 覆盖参数，请使用专用评测脚本");
        int agentCases = setting("MINEGUARD_EVAL_AGENT_CASES", 3, 1, 30);
        int safetyCases = setting("MINEGUARD_EVAL_SAFETY_CASES", 0, 0, 20);
        int supplementalCases = setting("MINEGUARD_EVAL_SUPPLEMENTAL_CASES", 0, 0, 12);
        boolean holdout = "holdout-v1".equals(System.getenv("MINEGUARD_EVAL_SUITE"));
        var frozen = holdout ? HoldoutGuard.verify(Path.of(""), new ObjectMapper()) : null;
        Path agentPath = holdout ? HoldoutGuard.CASES : Path.of("data/eval/agent_cases.json");
        if (holdout) { agentCases = 24; safetyCases = 0; supplementalCases = 0; }
        String runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
        Path output = Path.of("data/runtime/real-model-eval", runId).toAbsolutePath();
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("mode", holdout ? "Prospective Holdout Evaluation" : "Real Model Evaluation");
        if (holdout) {
            report.put("holdoutManifest", frozen);
            report.put("holdoutManifestSha256", HoldoutGuard.textHash(HoldoutGuard.MANIFEST));
            report.put("developerVisible", true);
        }
        report.put("startedAt", Instant.now());
        report.put("requestedAgentCases", agentCases);
        report.put("requestedSafetyCases", safetyCases);
        report.put("requestedSupplementalCases", supplementalCases);
        report.put("planningContract", PlanningContract.VERSION);
        report.put("promptSha256", Digests.sha256(PlanningContract.SYSTEM_PROMPT));
        report.put("agentCasesSha256", Digests.sha256(Files.readString(agentPath)));
        report.put("supplementalCasesSha256", Digests.sha256(Files.readString(Path.of("data/eval/agent_supplemental_cases.json"))));
        report.put("toolEnvironment", "合成事件数据、内存向量库、MockIndustrialGateway；不代表真实工业验收");
        report.put("baseline", "NOT RUN：现有关键词静态基线不参与真实模型端到端对比");
        report.put("cost", "未计算费用；调用次数限制不是货币限额，以服务商账单为准");
        var writer = new ObjectMapper().findAndRegisterModules();
        OpenAiCompatibleAgentModelClient model = null;
        String status = "ABORTED";
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MineGuardApplication.class)
                .web(WebApplicationType.NONE).run(isolationArguments(output))) {
            if (!(context.getBean(IndustrialGateway.class) instanceof MockIndustrialGateway)) {
                throw new IllegalStateException("真实模型评测禁止使用真实工业网关");
            }
            model = (OpenAiCompatibleAgentModelClient) context.getBean(AgentModelClient.class);
            MineGuardProperties.Llm config = context.getBean(MineGuardProperties.class).llm();
            if (config.maxCalls() <= 0) throw new IllegalStateException("尚未授权模型调用次数");
            if (holdout) {
                HoldoutGuard.requireFrozenModel(config, frozen);
                HoldoutGuard.claimAttempt(Path.of(""), runId);
            }
            report.put("provider", model.providerName());
            report.put("maxOutputTokens", config.maxOutputTokens());
            report.put("requestTimeoutSeconds", config.requestTimeoutSeconds());
            report.put("thinking", config.thinking() == null ? "" : config.thinking());
            // 规划最多包含一次修复；评测等待窗口应覆盖两次完整的请求超时。
            Duration timeout = Duration.ofSeconds(2L * config.requestTimeoutSeconds() + 15);
            report.put("agent", context.getBean(AgentEvaluator.class).evaluate(agentPath, agentCases, timeout));
            report.put("usageAfterAgent", usageTotals(model));
            report.put("safety", context.getBean(SafetyEvaluator.class).evaluate(Path.of("data/eval/safety_cases.json"), safetyCases, timeout));
            report.put("usageAfterSafety", usageTotals(model));
            report.put("supplemental", context.getBean(AgentEvaluator.class).evaluate(Path.of("data/eval/agent_supplemental_cases.json"), supplementalCases, timeout));
            status = model.usageSnapshot().rejectedByBudget() > 0 ? "INCOMPLETE_BUDGET" : "COMPLETED";
        } catch (Exception ex) {
            // 不把模型文本或外部异常正文写入报告。异常时也保留已经取得的用量回执。
            report.put("failureType", ex.getClass().getSimpleName());
        } finally {
            report.put("status", status);
            report.put("finishedAt", Instant.now());
            if (model != null) report.put("usage", model.usageSnapshot());
            writeReport(output, report, writer);
            System.out.println("真实模型评测状态：" + status + "；报告目录：" + output);
        }
        if (!"COMPLETED".equals(status)) throw new IllegalStateException("真实模型评测未完成，请检查独立报告中的状态和用量");
    }

    private static Map<String, Object> usageTotals(OpenAiCompatibleAgentModelClient model) {
        var usage = model.usageSnapshot();
        return Map.of("attempts", usage.attempts(), "promptTokens", usage.recordedPromptTokens(),
                "completionTokens", usage.recordedCompletionTokens(), "totalTokens", usage.recordedTotalTokens(),
                "unknownUsageRequests", usage.requestsWithUnknownUsage());
    }

    static String[] isolationArguments(Path output) {
        // 使用固定命令行配置覆盖外部环境，避免演示初始化误写已有 PostgreSQL 或 Milvus。
        return new String[]{"--spring.profiles.active=real-eval", "--mineguard.llm.provider=openai-compatible",
                "--spring.datasource.url=jdbc:h2:mem:mineguard_real_eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--mineguard.vector-store.type=in-memory", "--mineguard.embedding.provider=hashing", "--mineguard.knowledge-path=data/knowledge",
                "--mineguard.industrial.type=mock", "--mineguard.runtime.scheduler-enabled=true",
                "--mineguard.demo-data-enabled=true",
                "--mineguard.trace-path=" + output.resolve("traces")};
    }

    static int setting(String name, int fallback, int min, int max) {
        String value = System.getenv(name);
        int number = value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        if (number < min || number > max) throw new IllegalArgumentException(name + " 超出允许范围");
        return number;
    }

    static void writeReport(Path output, Map<String, Object> report, ObjectMapper mapper) throws Exception {
        Files.createDirectories(output);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), report);
        ModelUsageRecorder.Snapshot usage = (ModelUsageRecorder.Snapshot) report.get("usage");
        String text = """
                # 真实模型评测记录

                - 状态：%s（COMPLETED 仅表示用例执行结束，不表示所有用例通过）
                - 模式：Real Model Evaluation
                - 工具环境：合成数据、内存向量库、模拟工业网关。
                - 详细用例结果：同目录 `report.json`。
                - Token 口径：仅保存供应商 usage 回执，包含计划修复请求；没有回执时为未知，不是零。
                - 不保存模型思维链，不覆盖 `docs/eval/latest.json` 或确定性报告。
                - 小样本用于连通性检查，不能当作完整评测或直接写入简历。
                - 账本与额度仅限本次进程，重跑需要重新授权；没有实现货币金额硬限额或跨进程累计额度。

                ## 本次调用记录

                %s
                """.formatted(report.get("status"), usage == null ? "尚未取得模型用量。" :
                "请求次数：" + usage.attempts() + "/" + usage.maxCalls() + "；缺少完整用量的请求：" + usage.requestsWithUnknownUsage()
                        + "；已记录输入/输出/总 Token：" + usage.recordedPromptTokens() + "/" + usage.recordedCompletionTokens() + "/" + usage.recordedTotalTokens()
                        + "。null 表示未知；部分回执之和不代表整轮总消耗。");
        Files.writeString(output.resolve("REPORT.md"), text, StandardCharsets.UTF_8);
    }
}
