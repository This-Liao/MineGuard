package com.mineguard.rag;

public record DocumentChunk(String documentId, String title, String chunkId, String content) {}
