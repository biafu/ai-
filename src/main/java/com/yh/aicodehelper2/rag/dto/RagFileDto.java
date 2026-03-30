package com.yh.aicodehelper2.rag.dto;

public record RagFileDto(
        String id,
        String originalName,
        String extension,
        long sizeBytes,
        long uploadedAt
) {
}
