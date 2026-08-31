package com.mineguard.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RetrievalBenchmarkTest {
    @Test void recallCountsAllRelevantDocumentsRatherThanAnyHit() {
        var result = RetrievalBenchmark.score("R", "问题", List.of("A", "B"), List.of("X", "A", "Z", "Q", "W"));
        assertThat(result.recallAt1()).isZero();
        assertThat(result.recallAt3()).isEqualTo(0.5);
        assertThat(result.recallAt5()).isEqualTo(0.5);
        assertThat(result.reciprocalRankAt5()).isEqualTo(0.5);
        assertThat(result.ndcgAt5()).isBetween(0.38, 0.39);
    }
    @Test void deduplicatesChunksAndHandlesNoHit() {
        var perfect = RetrievalBenchmark.score("R", "问题", List.of("A", "A", "B"), List.of("A", "A", "B"));
        assertThat(perfect.actualDocumentIds()).containsExactly("A", "B");
        assertThat(perfect.recallAt1()).isEqualTo(0.5);
        assertThat(perfect.recallAt3()).isEqualTo(1);
        assertThat(perfect.ndcgAt5()).isEqualTo(1);
        var miss = RetrievalBenchmark.score("R", "问题", List.of("A"), List.of("X"));
        assertThat(miss.recallAt5()).isZero();
        assertThat(miss.reciprocalRankAt5()).isZero();
        assertThat(miss.ndcgAt5()).isZero();
        assertThatThrownBy(() -> RetrievalBenchmark.score("R", "问题", List.of(), List.of("X")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
