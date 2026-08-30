package com.mineguard.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.rag.Evidence;
import com.mineguard.rag.KnowledgeRetriever;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class RetrievalEvaluator {
    private final KnowledgeRetriever retriever;
    private final ObjectMapper mapper;

    public RetrievalEvaluator(KnowledgeRetriever retriever, ObjectMapper mapper) {
        this.retriever = retriever;
        this.mapper = mapper;
    }

    public Result evaluate(Path casesPath) {
        try {
            List<Case> cases = mapper.readValue(casesPath.toFile(), new TypeReference<>() {});
            int hit1 = 0, hit3 = 0, hit5 = 0;
            double reciprocalRanks = 0;
            List<CaseResult> details = new ArrayList<>();
            for (Case testCase : cases) {
                List<Evidence> evidence = retriever.retrieve(testCase.query(), 5);
                List<String> actual = evidence.stream().map(Evidence::documentId).toList();
                int rank = firstRelevantRank(actual, testCase.expectedDocumentIds());
                if (rank == 1) hit1++;
                if (rank > 0 && rank <= 3) hit3++;
                if (rank > 0 && rank <= 5) hit5++;
                if (rank > 0) reciprocalRanks += 1.0 / rank;
                details.add(new CaseResult(testCase.id(), testCase.query(), testCase.expectedDocumentIds(), actual, rank));
            }
            int count = cases.size();
            return new Result(count, ratio(hit1, count), ratio(hit3, count), ratio(hit5, count),
                    round(count == 0 ? 0 : reciprocalRanks / count), details);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read retrieval cases: " + casesPath, ex);
        }
    }

    private int firstRelevantRank(List<String> actual, List<String> expected) {
        for (int i = 0; i < actual.size(); i++) if (expected.contains(actual.get(i))) return i + 1;
        return 0;
    }

    private double ratio(int value, int count) { return round(count == 0 ? 0 : (double) value / count); }
    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }

    public record Case(String id, String query, List<String> expectedDocumentIds) {}
    public record CaseResult(String id, String query, List<String> expectedDocumentIds, List<String> actualDocumentIds, int firstRelevantRank) {}
    public record Result(int caseCount, double recallAt1, double recallAt3, double recallAt5, double mrr, List<CaseResult> cases) {}
}
