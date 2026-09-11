package com.mineguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 混合检索参数；candidateK 是每条召回通道参与融合的候选数量。 */
@ConfigurationProperties(prefix = "mineguard.retrieval")
public record RetrievalProperties(String mode, int candidateK, int rrfK) {
    public RetrievalProperties {
        mode = mode == null || mode.isBlank() ? "hybrid" : mode.toLowerCase();
        if (!java.util.Set.of("vector", "bm25", "hybrid").contains(mode)) {
            throw new IllegalArgumentException("检索模式只能是 vector、bm25 或 hybrid");
        }
        if (candidateK < 1 || rrfK < 1) throw new IllegalArgumentException("检索候选数和 RRF 常数必须为正数");
    }

    public static RetrievalProperties vectorDefaults() {
        return new RetrievalProperties("vector", 20, 60);
    }
}
