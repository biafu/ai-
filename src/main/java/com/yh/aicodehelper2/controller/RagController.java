package com.yh.aicodehelper2.controller;

import com.yh.aicodehelper2.rag.RagTaskService;
import com.yh.aicodehelper2.rag.dto.RagFilesDto;
import com.yh.aicodehelper2.rag.dto.RagTaskDto;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Resource
    private RagTaskService ragTaskService;

    @GetMapping("/files")
    public RagFilesDto listFiles() {
        return ragTaskService.listFiles();
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RagTaskDto> uploadFile(@RequestPart("file") MultipartFile file) {
        RagTaskDto task = ragTaskService.submitUpload(file);
        return ResponseEntity.accepted().body(task);
    }

    @GetMapping("/tasks/{taskId}")
    public RagTaskDto getTask(@PathVariable String taskId) {
        return ragTaskService.getTask(taskId);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleError(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", exception.getMessage() == null ? "请求失败" : exception.getMessage()
        ));
    }
}
