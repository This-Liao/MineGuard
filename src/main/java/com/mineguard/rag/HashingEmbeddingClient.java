package com.mineguard.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HashingEmbeddingClient implements EmbeddingClient {
    private static final int DIMENSIONS = 768;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String token : tokens(text == null ? "" : text.toLowerCase(Locale.ROOT))) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSIONS);
            vector[index] += (hash & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private List<String> tokens(String text) {
        String normalized = text.replaceAll("[^\\p{IsHan}a-z0-9_]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) continue;
            tokens.add(part);
            if (part.chars().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] chars = part.codePoints().toArray();
                for (int i = 0; i < chars.length; i++) tokens.add(new String(chars, i, 1));
                for (int i = 0; i + 1 < chars.length; i++) tokens.add(new String(chars, i, 2));
            }
        }
        return tokens;
    }
}
