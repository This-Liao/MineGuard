package com.mineguard.rag;

public interface EmbeddingClient {
    float[] embed(String text);
    int dimensions();
}
