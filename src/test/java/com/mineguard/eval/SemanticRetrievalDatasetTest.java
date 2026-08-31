package com.mineguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mineguard.config.MineGuardProperties;
import com.mineguard.rag.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SemanticRetrievalDatasetTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<KnowledgeDocument> documents = new KnowledgeLoader(new MineGuardProperties(null, null, "data/knowledge", "", 1)).load();
    private ArrayNode cases() throws Exception { return (ArrayNode) mapper.readTree(Path.of("data/eval/retrieval_holdout_v1.json").toFile()); }
    @Test void independentQueriesHaveExistingLabelsAndNoExactBaselineDuplicates() throws Exception {
        ArrayNode cases = cases();
        SemanticRetrievalEvalApplication.validateCases(cases, documents);
        Set<String> old = new HashSet<>();
        mapper.readTree(Path.of("data/eval/retrieval_cases.json").toFile()).forEach(row -> old.add(row.path("query").asText()));
        cases.forEach(row -> assertThat(old).doesNotContain(row.path("query").asText()));
        assertThat(documents).hasSize(20);
    }
    @Test void rejectsMissingDocumentsDuplicatesAndWrongDenominator() throws Exception {
        ArrayNode cases = cases(); cases.remove(0);
        assertThatThrownBy(() -> SemanticRetrievalEvalApplication.validateCases(cases, documents)).hasMessageContaining("30");
        ArrayNode duplicate = cases(); duplicate.set(0, duplicate.get(1));
        assertThatThrownBy(() -> SemanticRetrievalEvalApplication.validateCases(duplicate, documents)).hasMessageContaining("重复");
        ArrayNode unknown = cases(); ((ArrayNode) unknown.get(0).get("expectedDocumentIds")).add("unknown-document");
        assertThatThrownBy(() -> SemanticRetrievalEvalApplication.validateCases(unknown, documents)).hasMessageContaining("不存在");
    }
}
