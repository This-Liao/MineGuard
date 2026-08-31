package com.mineguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "mineguard.llm.provider=deterministic")
class EvaluationReportTest {
    @Autowired EvaluationOrchestrator orchestrator;
    @Autowired ObjectMapper mapper;
    @TempDir Path output;

    @Test
    void rendersChineseReportsFromRecordedResultsWithoutChangingMetrics() throws Exception {
        // 从已有实测快照渲染到临时目录，不重跑评测、不访问真实模型，也不覆盖仓库结果。
        Path recorded = Path.of("docs/eval/latest.json");
        EvaluationOrchestrator.Snapshot snapshot = mapper.readValue(recorded.toFile(), EvaluationOrchestrator.Snapshot.class);
        Files.createDirectories(output.resolve("docs"));
        for (String name : List.of("EVAL_REPORT.md", "REAL_MODEL_EVAL.md", "RESUME_METRICS.md")) {
            Files.writeString(output.resolve("docs").resolve(name), "当前真实模型指标，不得被离线运行覆盖");
        }
        orchestrator.writeArtifacts(output, snapshot);

        for (String name : List.of("EVAL_REPORT.md", "REAL_MODEL_EVAL.md", "RESUME_METRICS.md")) {
            assertThat(Files.readString(output.resolve("docs").resolve(name))).as(name)
                    .isEqualTo("当前真实模型指标，不得被离线运行覆盖");
        }
        assertThat(Files.readString(output.resolve("docs/eval/deterministic-report.md")))
                .contains("离线模式", "构建与测试", "不是端到端执行成功率", "当前评测总览");
        assertThat(Files.readString(output.resolve("docs/DETERMINISTIC_EVAL.md"))).contains("离线确定性规划器和哈希向量化");

        JsonNode original = mapper.readTree(recorded.toFile());
        JsonNode rendered = mapper.readTree(output.resolve("docs/eval/latest.json").toFile());
        for (String field : List.of("generatedAt", "tests", "retrieval", "agent", "safety", "baseline", "realModelEvaluation")) {
            assertThat(rendered.path(field)).as(field).isEqualTo(original.path(field));
        }
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").strip();
    }
}
