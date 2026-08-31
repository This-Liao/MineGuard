package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.rag.Evidence;
import com.mineguard.rag.KnowledgeRetriever;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 文档级多相关项评分：Recall 不再等同于命中任意一个相关项的 HitRate。 */
public final class RetrievalBenchmark {
    private RetrievalBenchmark() {}
    public static Result evaluate(KnowledgeRetriever retriever, Path casesPath, ObjectMapper mapper) throws IOException {
        List<RetrievalEvaluator.Case> cases = mapper.readValue(casesPath.toFile(), new TypeReference<>() {});
        if (cases.isEmpty()) throw new IllegalArgumentException("检索评测不能为空");
        List<CaseResult> details = new ArrayList<>();
        for (var item : cases) {
            // 按文档去重后取前五；该小语料索引可完整取回，不把重复片段当成多篇文档。
            List<String> actual = retriever.retrieve(item.query(), Math.max(5, retriever.indexedChunkCount())).stream()
                    .map(Evidence::documentId).distinct().limit(5).toList();
            details.add(score(item.id(), item.query(), item.expectedDocumentIds(), actual));
        }
        return new Result(details.size(), average(details, 1), average(details, 3), average(details, 5),
                details.stream().mapToDouble(c -> c.reciprocalRankAt5).average().orElseThrow(),
                details.stream().mapToDouble(c -> c.ndcgAt5).average().orElseThrow(),
                details.stream().filter(c -> c.recallAt5 > 0).count() / (double) details.size(), details);
    }
    static CaseResult score(String id, String query, List<String> relevant, List<String> actual) {
        Set<String> expected = new LinkedHashSet<>(relevant);
        if (expected.isEmpty() || expected.contains(null)) throw new IllegalArgumentException("必须标注相关文档");
        List<String> ranked = actual.stream().distinct().limit(5).toList();
        double dcg = 0, ideal = 0, rr = 0;
        for (int i = 0; i < ranked.size(); i++) if (expected.contains(ranked.get(i))) {
            dcg += 1 / log2(i + 2);
            if (rr == 0) rr = 1.0 / (i + 1);
        }
        for (int i = 0; i < Math.min(5, expected.size()); i++) ideal += 1 / log2(i + 2);
        return new CaseResult(id, query, List.copyOf(expected), ranked, recall(ranked, expected, 1),
                recall(ranked, expected, 3), recall(ranked, expected, 5), rr, dcg / ideal);
    }
    private static double recall(List<String> actual, Set<String> expected, int k) {
        return actual.stream().limit(k).filter(expected::contains).count() / (double) expected.size();
    }
    private static double log2(int n) { return Math.log(n) / Math.log(2); }
    private static double average(List<CaseResult> details, int k) {
        return details.stream().mapToDouble(c -> k == 1 ? c.recallAt1 : k == 3 ? c.recallAt3 : c.recallAt5).average().orElseThrow();
    }
    public record CaseResult(String id, String query, List<String> expectedDocumentIds, List<String> actualDocumentIds,
                             double recallAt1, double recallAt3, double recallAt5, double reciprocalRankAt5, double ndcgAt5) {}
    public record Result(int caseCount, double recallAt1, double recallAt3, double recallAt5,
                         double mrrAt5, double ndcgAt5, double hitRateAt5, List<CaseResult> cases) {}
}
