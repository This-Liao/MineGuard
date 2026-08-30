package com.mineguard.rag;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeRetriever {
    private static final int MAX_CHARS = 900;
    private static final int OVERLAP = 120;
    private final KnowledgeLoader loader;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public KnowledgeRetriever(KnowledgeLoader loader, EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.loader = loader;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void index() {
        List<VectorStore.VectorEntry> entries = loader.load().stream()
                .flatMap(document -> chunk(document).stream())
                .map(chunk -> new VectorStore.VectorEntry(chunk, embeddingClient.embed(chunk.title() + " " + chunk.content())))
                .toList();
        vectorStore.replaceAll(entries);
    }

    public List<Evidence> retrieve(String query, int topK) {
        return vectorStore.search(embeddingClient.embed(query), topK).stream()
                .map(match -> new Evidence(match.chunk().documentId(), match.chunk().title(), match.chunk().chunkId(),
                        Math.round(match.score() * 10_000d) / 10_000d, match.chunk().content()))
                .toList();
    }

    public int indexedChunkCount() {
        return vectorStore.size();
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
}
