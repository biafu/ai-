package com.yh.aicodehelper2.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagFileMeta {
    private String id;
    private String originalName;
    private String storedName;
    private String extension;
    private long sizeBytes;
    private long uploadedAt;
}
