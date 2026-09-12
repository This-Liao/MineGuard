package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.rag.KnowledgeDocument;
import com.mineguard.rag.KnowledgeLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRetrievalV3EvalApplicationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 扩展集与独立鲁棒性集满足冻结结构() throws Exception {
        List<HybridRetrievalV3EvalApplication.EvalCase> expanded = read(
                HybridRetrievalV3EvalApplication.EXPANDED_CASES);
        List<HybridRetrievalV3EvalApplication.EvalCase> robustness = read(
                HybridRetrievalV3EvalApplication.ROBUSTNESS_CASES);

        HybridRetrievalV3EvalApplication.validateDatasets(expanded, robustness, documents());

        assertThat(expanded).hasSize(80);
        assertThat(robustness).hasSize(64);
        assertThat(robustness).extracting(HybridRetrievalV3EvalApplication.EvalCase::groupId)
                .doesNotContainNull().doesNotContain("");
    }

    @Test
    void 鲁棒性意图组必须保持相同相关文档标注() throws Exception {
        List<HybridRetrievalV3EvalApplication.EvalCase> expanded = read(
                HybridRetrievalV3EvalApplication.EXPANDED_CASES);
        List<HybridRetrievalV3EvalApplication.EvalCase> robustness = new ArrayList<>(read(
                HybridRetrievalV3EvalApplication.ROBUSTNESS_CASES));
        var first = robustness.getFirst();
        robustness.set(0, new HybridRetrievalV3EvalApplication.EvalCase(first.id(), first.category(),
                first.groupId(), first.variantType(), first.query(), List.of("K001-personnel-violation")));

        assertThatThrownBy(() -> HybridRetrievalV3EvalApplication.validateDatasets(
                expanded, robustness, documents())).hasMessageContaining("意图组标注");
    }

    @Test
    void 配对比较会分别记录RRF挽救与损伤() {
        var baseline = List.of(
                scored("A", List.of("X"), List.of("N")),
                scored("B", List.of("Y"), List.of("Y")));
        var candidate = List.of(
                scored("A", List.of("X"), List.of("X")),
                scored("B", List.of("Y"), List.of("N")));

        Map<String, Object> result = HybridRetrievalV3EvalApplication.pairedComparison(baseline, candidate);

        assertThat(result).containsEntry("recallAt5ImprovedCases", 1)
                .containsEntry("recallAt5DegradedCases", 1)
                .containsEntry("baselineMissCandidateHitCases", 1)
                .containsEntry("baselineHitCandidateMissCases", 1);
        assertThat(result.get("improvedCaseIds")).isEqualTo(List.of("A"));
        assertThat(result.get("degradedCaseIds")).isEqualTo(List.of("B"));
    }

    @Test
    void 鲁棒性汇总计算最差变体和排名波动() {
        List<HybridRetrievalV3EvalApplication.EvalCase> cases = List.of(
                item("A-B", "BASE"), item("A-P", "PARAPHRASE"),
                item("A-T", "TERM_VARIATION"), item("A-N", "DISTRACTOR_NOISE"));
        List<RetrievalBenchmark.CaseResult> results = List.of(
                scored("A-B", List.of("X"), List.of("X")),
                scored("A-P", List.of("X"), List.of("N", "X")),
                scored("A-T", List.of("X"), List.of("N")),
                scored("A-N", List.of("X"), List.of("N", "N2", "X")));

        Map<String, Object> summary = HybridRetrievalV3EvalApplication.robustnessSummary(results, cases);

        assertThat(summary).containsEntry("groupCount", 1)
                .containsEntry("allVariantsHitAt5Rate", 0.0)
                .containsEntry("meanWorstVariantRecallAt5", 0.0)
                .containsEntry("meanFirstRelevantRankRange", 5.0);
    }

    private List<HybridRetrievalV3EvalApplication.EvalCase> read(Path path) throws Exception {
        return mapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    private List<KnowledgeDocument> documents() {
        List<KnowledgeDocument> result = new ArrayList<>(loader("data/knowledge").load());
        result.addAll(loader("data/eval/hybrid_retrieval_v3/knowledge").load());
        return result;
    }

    private KnowledgeLoader loader(String path) {
        return new KnowledgeLoader(new MineGuardProperties(null, null, path, "", 1));
    }

    private RetrievalBenchmark.CaseResult scored(String id, List<String> expected, List<String> actual) {
        return RetrievalBenchmark.score(id, id, expected, actual);
    }

    private HybridRetrievalV3EvalApplication.EvalCase item(String id, String variant) {
        return new HybridRetrievalV3EvalApplication.EvalCase(
                id, "SEMANTIC", "A", variant, id, List.of("X"));
    }
}
