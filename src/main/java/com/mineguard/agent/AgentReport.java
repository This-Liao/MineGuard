package com.mineguard.agent;

import java.util.List;

/** 汇报中的陈述与来源分离存储，引用编号由后端生成，不由模型编造。 */
public record AgentReport(String version, String summary, List<Section> sections,
                          List<Citation> citations, List<String> notes) {
    public record Section(String key, String title, List<Statement> statements) {}
    public record Statement(String text, List<Integer> citationIds) {}
    public record Citation(int id, String kind, String title, String documentId, String chunkId,
                           Double score, String content, Integer toolCallIndex) {}
}
