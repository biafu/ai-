package com.yh.aicodehelper2.rag.model;

import java.util.List;

public record RagEnqueueResult(
        RagFileMeta uploadedFile,
        RagFileMeta replacedFile,
        List<RagFileMeta> files
) {
}
