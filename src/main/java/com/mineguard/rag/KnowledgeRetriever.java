package com.mineguard.rag;

import com.mineguard.config.RetrievalProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Component
public class KnowledgeRetriever {
    private static final int MAX_CHARS = 900;
    private static final int OVERLAP = 120;
    private final KnowledgeLoader loader;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final LuceneBm25Index bm25Index;
    private final Executor retrievalExecutor;
    private final RetrievalProperties properties;

    public KnowledgeRetriever(KnowledgeLoader loader, EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this(loader, embeddingClient, vectorStore, new LuceneBm25Index(), Runnable::run,
                RetrievalProperties.vectorDefaults());
    }

    @Autowired
    public KnowledgeRetriever(KnowledgeLoader loader, EmbeddingClient embeddingClient, VectorStore vectorStore,
                              LuceneBm25Index bm25Index, @Qualifier("retrievalExecutor") Executor retrievalExecutor,
                              RetrievalProperties properties) {
        this.loader = loader;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.retrievalExecutor = retrievalExecutor;
        this.properties = properties;
    }

    @PostConstruct
    public void index() {
        List<DocumentChunk> chunks = loader.load().stream()
                .flatMap(document -> chunk(document).stream())
                .toList();
        List<float[]> vectors = embeddingClient.embedDocuments(chunks.stream().map(c -> c.title() + " " + c.content()).toList());
        if (vectors.size() != chunks.size()) throw new IllegalStateException("文档与向量数量不一致");
        List<VectorStore.VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (vectors.get(i).length != embeddingClient.dimensions()) throw new IllegalStateException("文档向量维度不一致");
            entries.add(new VectorStore.VectorEntry(chunks.get(i), vectors.get(i)));
        }
        vectorStore.replaceAll(entries);
        bm25Index.replaceAll(chunks);
    }

    public List<Evidence> retrieve(String query, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK 必须为正数");
        return switch (properties.mode()) {
            case "vector" -> vectorRanking(query, topK).stream().map(RankedEvidence::evidence).toList();
            case "bm25" -> bm25Ranking(query, topK).stream().map(RankedEvidence::evidence).toList();
            default -> retrieveDetailed(query, topK).fused().stream().map(RankedEvidence::evidence).toList();
        };
    }

    /** 并行执行两条召回通道，并保留各自排名与 RRF 融合排名用于评测和溯源。 */
    public HybridRetrievalResult retrieveDetailed(String query, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK 必须为正数");
        int candidates = Math.max(topK, properties.candidateK());
        CompletableFuture<List<RankedEvidence>> vector = CompletableFuture.supplyAsync(
                () -> vectorRanking(query, candidates), retrievalExecutor);
        CompletableFuture<List<RankedEvidence>> bm25 = CompletableFuture.supplyAsync(
                () -> bm25Ranking(query, candidates), retrievalExecutor);
        try {
            List<RankedEvidence> vectorRank = vector.join();
            List<RankedEvidence> bm25Rank = bm25.join();
            return new HybridRetrievalResult(vectorRank, bm25Rank, fuse(vectorRank, bm25Rank, topK));
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw ex;
        }
    }

    public int indexedChunkCount() {
        return vectorStore.size();
    }

    public int bm25IndexedChunkCount() {
        return bm25Index.size();
    }

    private List<RankedEvidence> vectorRanking(String query, int topK) {
        return rank(vectorStore.search(embeddingClient.embedQuery(query), topK).stream()
                .map(match -> new ScoredChunk(match.chunk(), match.score())).toList());
    }

    private List<RankedEvidence> bm25Ranking(String query, int topK) {
        return rank(bm25Index.search(query, topK).stream()
                .map(match -> new ScoredChunk(match.chunk(), match.score())).toList());
    }

    private List<RankedEvidence> rank(List<ScoredChunk> matches) {
        List<RankedEvidence> ranked = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            ScoredChunk match = matches.get(i);
            Evidence evidence = evidence(match.chunk(), match.score());
            ranked.add(new RankedEvidence(i + 1, match.score(), 0, evidence));
        }
        return List.copyOf(ranked);
    }

    private List<RankedEvidence> fuse(List<RankedEvidence> vector, List<RankedEvidence> bm25, int topK) {
        Map<String, Fusion> scores = new LinkedHashMap<>();
        addRrf(scores, vector);
        addRrf(scores, bm25);
        return scores.values().stream()
                .sorted(Comparator.comparingDouble(Fusion::score).reversed()
                        .thenComparingInt(Fusion::bestRank)
                        .thenComparing(item -> item.evidence().chunkId()))
                .limit(topK)
                .map(new java.util.function.Function<Fusion, RankedEvidence>() {
                    private int rank;
                    @Override public RankedEvidence apply(Fusion item) {
                        double score = round(item.score(), 8);
                        return new RankedEvidence(++rank, score, score,
                                new Evidence(item.evidence().documentId(), item.evidence().title(),
                                        item.evidence().chunkId(), score, item.evidence().content()));
                    }
                }).toList();
    }

    private void addRrf(Map<String, Fusion> scores, List<RankedEvidence> ranking) {
        for (RankedEvidence item : ranking) {
            double contribution = 1d / (properties.rrfK() + item.rank());
            scores.compute(item.evidence().chunkId(), (ignored, old) -> old == null
                    ? new Fusion(item.evidence(), contribution, item.rank())
                    : new Fusion(old.evidence(), old.score() + contribution, Math.min(old.bestRank(), item.rank())));
        }
    }

    private Evidence evidence(DocumentChunk chunk, double score) {
        return new Evidence(chunk.documentId(), chunk.title(), chunk.chunkId(), round(score, 4), chunk.content());
    }

    private double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private List<DocumentChunk> chunk(KnowledgeDocument document) {
        String content = document.content();
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 1;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + MAX_CHARS);
            if (end < content.length()) {
                int paragraph = content.lastIndexOf("\n\n", end);
                if (paragraph > start + MAX_CHARS / 2) end = paragraph;
            }
            chunks.add(new DocumentChunk(document.documentId(), document.title(),
                    document.documentId() + "-chunk-" + index++, content.substring(start, end).trim()));
            if (end == content.length()) break;
            start = Math.max(start + 1, end - OVERLAP);
        }
        return chunks;
    }

    public record RankedEvidence(int rank, double sourceScore, double rrfScore, Evidence evidence) {}
    public record HybridRetrievalResult(List<RankedEvidence> vector, List<RankedEvidence> bm25,
                                        List<RankedEvidence> fused) {}
    private record ScoredChunk(DocumentChunk chunk, double score) {}
    private record Fusion(Evidence evidence, double score, int bestRank) {}
}
