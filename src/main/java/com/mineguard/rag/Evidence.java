package com.mineguard.rag;

public record Evidence(String documentId, String title, String chunkId, double score, String content) {}
