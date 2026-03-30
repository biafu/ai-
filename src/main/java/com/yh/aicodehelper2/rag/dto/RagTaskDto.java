package com.yh.aicodehelper2.rag.dto;

public record RagTaskDto(
        String taskId,
        String status,
        String stage,
        int progress,
        String message,
        RagFileDto uploadedFile,
        RagFileDto replacedFile,
        long createdAt,
        long updatedAt
) {
}
