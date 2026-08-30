package com.mineguard.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KnowledgeRetrieverIntegrationTest {
    @Autowired KnowledgeRetriever retriever;

    @Test
    void indexesSyntheticKnowledgeCorpus() {
        assertThat(retriever.indexedChunkCount()).isBetween(20, 60);
    }

    @Test
    void retrievesGasEvidenceWithStableIdentity() {
        var evidence = retriever.retrieve("瓦斯异常告警处置与传感器复测", 5);
        assertThat(evidence).hasSize(5);
        assertThat(evidence).extracting(Evidence::documentId).containsAnyOf("K002-gas-warning", "K016-sensor-calibration");
        assertThat(evidence).allSatisfy(item -> {
            assertThat(item.chunkId()).isNotBlank();
            assertThat(item.content()).contains("Synthetic Demo Data");
        });
    }
}
