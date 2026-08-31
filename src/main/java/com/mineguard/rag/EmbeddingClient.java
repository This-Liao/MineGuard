package com.mineguard.rag;

import java.util.List;

public interface EmbeddingClient {
    float[] embed(String text);
    int dimensions();
    default float[] embedQuery(String text) { return embed(text); }
    default List<float[]> embedDocuments(List<String> texts) { return texts.stream().map(this::embed).toList(); }
}
