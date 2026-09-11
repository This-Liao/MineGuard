package com.mineguard.rag;

import com.mineguard.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridKnowledgeRetrieverTest {
    @Test
    void 应分别输出向量BM25与RRF排名() {
        KnowledgeLoader loader = mock(KnowledgeLoader.class);
        when(loader.load()).thenReturn(List.of(
                document("A", "安全帽佩戴规范", "进入采区必须佩戴安全帽。"),
                document("B", "摄像头维护", "camera-19 网络故障排查。"),
                document("C", "算法管理", "intrusion_detection 参数说明。")));
        EmbeddingClient embedding = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[]{1, 0}; }
            @Override public List<float[]> embedDocuments(List<String> texts) {
                return texts.stream().map(ignored -> new float[]{1, 0}).toList();
            }
            @Override public float[] embedQuery(String text) { return new float[]{1, 0}; }
            @Override public int dimensions() { return 2; }
        };
        VectorStore vector = new OrderedVectorStore();
        try (var bm25 = new LuceneBm25Index(); var executor = Executors.newFixedThreadPool(2)) {
            var retriever = new KnowledgeRetriever(loader, embedding, vector, bm25, executor,
                    new RetrievalProperties("hybrid", 3, 60));
            retriever.index();

            var result = retriever.retrieveDetailed("安全帽", 3);
            assertThat(result.vector()).extracting(item -> item.evidence().documentId())
                    .containsExactly("B", "A", "C");
            assertThat(result.bm25()).extracting(item -> item.evidence().documentId())
                    .containsExactly("A");
            assertThat(result.fused().getFirst().evidence().documentId()).isEqualTo("A");
            assertThat(result.fused().getFirst().rrfScore())
                    .isEqualTo(Math.round((1d / 62 + 1d / 61) * 100_000_000d) / 100_000_000d);
            assertThat(retriever.retrieve("安全帽", 1).getFirst().documentId()).isEqualTo("A");
            assertThat(retriever.bm25IndexedChunkCount()).isEqualTo(retriever.indexedChunkCount());
        }
    }

    private KnowledgeDocument document(String id, String title, String content) {
        return new KnowledgeDocument(id, title, "# " + title + "\n\n" + content, true);
    }

    private static final class OrderedVectorStore implements VectorStore {
        private List<VectorEntry> entries = List.of();
        @Override public void replaceAll(List<VectorEntry> entries) { this.entries = List.copyOf(entries); }
        @Override public List<VectorMatch> search(float[] queryVector, int topK) {
            return List.of(entries.get(1), entries.get(0), entries.get(2)).stream()
                    .limit(topK).map(entry -> new VectorMatch(entry.chunk(), 0.9)).toList();
        }
        @Override public int size() { return entries.size(); }
    }
}
