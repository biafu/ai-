package com.yh.aicodehelper2.rag.dto;

import java.util.List;

public record RagFilesDto(
        int maxFiles,
        List<RagFileDto> files
) {
}
