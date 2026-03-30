package com.yh.aicodehelper2.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagQueueState {
    private List<RagFileMeta> files = new ArrayList<>();
}
