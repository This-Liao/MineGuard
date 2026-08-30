package com.mineguard.tool.impl;

import com.mineguard.rag.KnowledgeRetriever;
import com.mineguard.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SearchSafetyKnowledgeTool implements Tool {
    private final KnowledgeRetriever retriever;
    public SearchSafetyKnowledgeTool(KnowledgeRetriever retriever) { this.retriever = retriever; }
    @Override public String name() { return "search_safety_knowledge"; }
    @Override public String description() { return "Retrieve ranked synthetic safety knowledge with document/chunk evidence."; }
    @Override public ToolCategory category() { return ToolCategory.READ; }
    @Override public ToolSchema schema() {
        return new ToolSchema(Map.of("query", ToolSchema.Field.string(), "topK", ToolSchema.Field.integer()), Set.of("query"), false);
    }
    @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
        int topK = Math.min(10, Math.max(1, ToolArguments.integer(args, "topK", 5)));
        return ToolResult.success(retriever.retrieve(ToolArguments.string(args, "query"), topK));
    }
}
