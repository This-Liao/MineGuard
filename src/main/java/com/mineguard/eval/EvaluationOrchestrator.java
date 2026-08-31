package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.llm.AgentModelClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Component
public class EvaluationOrchestrator {
    private final RetrievalEvaluator retrievalEvaluator;
    private final AgentEvaluator agentEvaluator;
    private final SafetyEvaluator safetyEvaluator;
    private final BasicAgentBaselineEvaluator baselineEvaluator;
    private final TestReportReader testReportReader;
    private final AgentModelClient model;
    private final ObjectMapper mapper;

    public EvaluationOrchestrator(RetrievalEvaluator retrievalEvaluator, AgentEvaluator agentEvaluator,
                                  SafetyEvaluator safetyEvaluator, BasicAgentBaselineEvaluator baselineEvaluator,
                                  TestReportReader testReportReader, AgentModelClient model, ObjectMapper mapper) {
        this.retrievalEvaluator = retrievalEvaluator;
        this.agentEvaluator = agentEvaluator;
        this.safetyEvaluator = safetyEvaluator;
        this.baselineEvaluator = baselineEvaluator;
        this.testReportReader = testReportReader;
        this.model = model;
        this.mapper = mapper;
    }

    public Snapshot runAll() {
        if (model.realModel()) {
            throw new IllegalStateException("真实模型请使用 run-real-eval.ps1，防止覆盖确定性评测或触发未隔离的工具操作");
        }
        Path root = Path.of("").toAbsolutePath().normalize();
        RetrievalEvaluator.Result retrieval = retrievalEvaluator.evaluate(root.resolve("data/eval/retrieval_cases.json"));
        AgentEvaluator.Result agent = agentEvaluator.evaluate(root.resolve("data/eval/agent_cases.json"));
        SafetyEvaluator.Result safety = safetyEvaluator.evaluate(root.resolve("data/eval/safety_cases.json"));
        BasicAgentBaselineEvaluator.Result baseline = baselineEvaluator.evaluate(root.resolve("data/eval/agent_cases.json"));
        TestReportReader.Result tests = testReportReader.read(root.resolve("target/surefire-reports"));
        Object realModel = model.realModel() ? Map.of("status", "RUN", "provider", model.providerName(), "agent", agent)
                : Map.of("status", "NOT RUN", "reason", "No OpenAI-compatible model was configured for this evaluation run.");
        Snapshot snapshot = new Snapshot(Instant.now(), "Deterministic Evaluation", model.providerName(), tests,
                retrieval, agent, safety, baseline, realModel);
        writeArtifacts(root, snapshot);
        return snapshot;
    }

    void writeArtifacts(Path root, Snapshot snapshot) {
        try {
            Path evalDir = root.resolve("docs/eval");
            Files.createDirectories(evalDir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(evalDir.resolve("latest.json").toFile(), snapshot);
            mapper.writerWithDefaultPrettyPrinter().writeValue(evalDir.resolve("retrieval-latest.json").toFile(), snapshot.retrieval());
            // 离线运行只更新自身产物，不能覆盖当前指标入口。
            write(root.resolve("docs/eval/deterministic-report.md"), evalReport(snapshot));
            write(root.resolve("docs/DETERMINISTIC_EVAL.md"), deterministicReport(snapshot));
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write evaluation artifacts", ex);
        }
    }

    private String evalReport(Snapshot s) {
        return """
                # 确定性回归报告（离线模式）

                生成时间： %s

                本文件只记录本轮离线模型和哈希检索的结果，不代表真实 DeepSeek 的当前状态。当前指标见 [评测总览](../EVAL_REPORT.md)。

                ## 构建与测试

                - 测试总数： %d
                - 测试通过： %d
                - 测试失败： %d
                - 测试异常： %d
                - 测试跳过： %d

                ## 知识检索评测（%d 条用例）

                - Recall@1: %s
                - Recall@3: %s
                - Recall@5: %s
                - MRR: %s

                ## Agent 评测（%d 条用例）

                - 任务成功率： %s
                - 工具选择准确率： %s
                - 工具参数有效率： %s
                - 审批强制率： %s
                - 平均工具调用次数： %.2f
                - p50 任务耗时： %d ms
                - p95 任务耗时： %d ms
                - RAG 证据覆盖率： %s

                ## 安全评测（%d 条对抗用例）

                - 审批强制生效： %d/%d
                - 高风险操作审批绕过： %d/%d

                ## 简化基线静态评分（%d 条用例）

                基线仅按关键词选取一个工具并与期望工具静态匹配，不实际执行任务，也不调用模型。因此以下数值是静态匹配评分，不是端到端执行成功率。原始 JSON 中的调用数为代码预设值，不能作为实测调用指标。

                - 静态任务匹配评分：%s
                - 静态工具匹配准确率：%s
                - 预设工具调用数：%.2f（未实际执行，不作为运行指标）

                ## 真实模型评测

                %s
                """.formatted(s.generatedAt(), s.tests().testCount(), s.tests().passed(), s.tests().failures(), s.tests().errors(), s.tests().skipped(),
                s.retrieval().caseCount(), pct(s.retrieval().recallAt1()), pct(s.retrieval().recallAt3()), pct(s.retrieval().recallAt5()), dec(s.retrieval().mrr()),
                s.agent().caseCount(), pct(s.agent().taskSuccessRate()), pct(s.agent().toolSelectionAccuracy()), pct(s.agent().toolParameterValidRate()),
                pct(s.agent().approvalEnforcementRate()), s.agent().averageToolCalls(), s.agent().p50LatencyMs(), s.agent().p95LatencyMs(),
                pct(s.agent().ragEvidenceCoverage()), s.safety().caseCount(), s.safety().approvalEnforcedCount(), s.safety().caseCount(),
                s.safety().unsafeActionBypassCount(), s.safety().caseCount(), s.baseline().caseCount(), pct(s.baseline().taskSuccessRate()),
                pct(s.baseline().toolSelectionAccuracy()), s.baseline().averageToolCalls(), realStatus(s));
    }

    private String deterministicReport(Snapshot s) {
        return """
                # 确定性评测

                基于固定种子合成数据、离线确定性规划器和哈希向量化生成。

                - 检索用例：%d 条；Recall@5：%s；MRR：%s
                - Agent 用例：%d 条；任务成功率：%s；工具选择准确率：%s
                - 安全用例：%d 条；高风险操作审批绕过：%d/%d
                - 任务耗时：p50 %d ms；p95 %d ms（本机测量，包含工作流轮询）
                """.formatted(s.retrieval().caseCount(), pct(s.retrieval().recallAt5()), dec(s.retrieval().mrr()),
                s.agent().caseCount(), pct(s.agent().taskSuccessRate()), pct(s.agent().toolSelectionAccuracy()),
                s.safety().caseCount(), s.safety().unsafeActionBypassCount(), s.safety().caseCount(),
                s.agent().p50LatencyMs(), s.agent().p95LatencyMs());
    }


    private String realStatus(Snapshot s) { return "本轮为离线回归，不发起模型 API 请求。真实模型的独立归档见 [当前评测总览](../EVAL_REPORT.md)。"; }
    private String pct(double value) { return "%.2f%%".formatted(value * 100); }
    private String dec(double value) { return "%.4f".formatted(value); }
    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.strip() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    public record Snapshot(Instant generatedAt, String evaluationMode, String plannerProvider,
                           TestReportReader.Result tests, RetrievalEvaluator.Result retrieval,
                           AgentEvaluator.Result agent, SafetyEvaluator.Result safety,
                           BasicAgentBaselineEvaluator.Result baseline, Object realModelEvaluation) {}
}
