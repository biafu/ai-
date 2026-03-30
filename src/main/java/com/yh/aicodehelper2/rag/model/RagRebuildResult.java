package com.yh.aicodehelper2.rag.model;

public record RagRebuildResult(
        int sourceFileCount,
        int indexedDocumentCount
) {
}
