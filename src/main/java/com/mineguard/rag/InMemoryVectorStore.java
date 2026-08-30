package com.mineguard.rag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryVectorStore implements VectorStore {
    private final List<VectorEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void replaceAll(List<VectorEntry> values) {
        entries.clear();
        entries.addAll(values);
    }

    @Override
    public List<VectorMatch> search(float[] queryVector, int topK) {
        return entries.stream()
                .map(entry -> new VectorMatch(entry.chunk(), cosine(queryVector, entry.vector())))
                .sorted(java.util.Comparator.comparingDouble(VectorMatch::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    @Override
    public int size() {
        return entries.size();
    }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
