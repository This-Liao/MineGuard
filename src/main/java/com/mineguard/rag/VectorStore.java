package com.mineguard.rag;

import java.util.List;

public interface VectorStore {
    void replaceAll(List<VectorEntry> entries);
    List<VectorMatch> search(float[] queryVector, int topK);
    int size();

    record VectorEntry(DocumentChunk chunk, float[] vector) {}
    record VectorMatch(DocumentChunk chunk, double score) {}
}
