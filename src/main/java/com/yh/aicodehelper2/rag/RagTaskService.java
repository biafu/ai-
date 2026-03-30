package com.yh.aicodehelper2.rag;

import com.yh.aicodehelper2.rag.dto.RagFileDto;
import com.yh.aicodehelper2.rag.dto.RagFilesDto;
import com.yh.aicodehelper2.rag.dto.RagTaskDto;
import com.yh.aicodehelper2.rag.model.RagEnqueueResult;
import com.yh.aicodehelper2.rag.model.RagFileMeta;
import com.yh.aicodehelper2.rag.model.RagRebuildResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.InvalidPathException;

@Service
@Slf4j
public class RagTaskService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String STAGE_VALIDATING = "VALIDATING";
    private static final String STAGE_QUEUE_UPDATING = "QUEUE_UPDATING";
    private static final String STAGE_REINDEXING = "REINDEXING";
    private static final String STAGE_DONE = "DONE";
    private static final String STAGE_FAILED = "FAILED";

    private static final int MAX_TASKS = 200;

    private final RagKnowledgeBaseService knowledgeBaseService;
    private final RagIndexService ragIndexService;
    private final RagProperties ragProperties;

    private final ConcurrentHashMap<String, TaskState> taskStore = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> taskOrder = new ConcurrentLinkedDeque<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "rag-upload-worker");
        worker.setDaemon(true);
        return worker;
    });

    private Set<String> allowedExtensions;
    private long maxFileBytes;

    public RagTaskService(RagKnowledgeBaseService knowledgeBaseService, RagIndexService ragIndexService, RagProperties ragProperties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ragIndexService = ragIndexService;
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        Set<String> extensionSet = new HashSet<>();
        for (String extension : ragProperties.getAllowedExtensions()) {
            extensionSet.add(extension.toLowerCase(Locale.ROOT));
        }
        this.allowedExtensions = Set.copyOf(extensionSet);
        this.maxFileBytes = ragProperties.getMaxFileSizeMb() * 1024 * 1024;

        try {
            List<RagFileMeta> files = knowledgeBaseService.listFiles();
            RagRebuildResult result = ragIndexService.rebuild(files, knowledgeBaseService.getDocumentDir());
            log.info("RAG index initialized. sourceFiles={}, indexedDocuments={}",
                    result.sourceFileCount(), result.indexedDocumentCount());
        } catch (Exception exception) {
            log.error("Failed to initialize RAG index at startup", exception);
        }
    }

    @PreDestroy
    public void destroy() {
        executorService.shutdownNow();
    }

    public RagTaskDto submitUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        String extension = extractExtension(originalName);
        validateUpload(file, extension);

        Path stagedFile = null;
        try {
            stagedFile = knowledgeBaseService.createTempFile("." + extension);
            file.transferTo(stagedFile.toFile());
        } catch (IOException exception) {
            deleteStagedFile(stagedFile);
            throw new IllegalStateException("上传文件暂存失败", exception);
        } catch (RuntimeException exception) {
            deleteStagedFile(stagedFile);
            throw new IllegalStateException("上传文件暂存失败", exception);
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        TaskState taskState = new TaskState(taskId);
        taskStore.put(taskId, taskState);
        taskOrder.addLast(taskId);
        trimTaskStore();

        final Path finalStagedFile = stagedFile;
        executorService.submit(() -> processTask(taskState, finalStagedFile, originalName, extension, file.getSize()));
        return taskState.toDto();
    }

    public RagTaskDto getTask(String taskId) {
        TaskState taskState = taskStore.get(taskId);
        if (taskState == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return taskState.toDto();
    }

    public RagFilesDto listFiles() {
        List<RagFileDto> files = knowledgeBaseService.listFiles().stream()
                .sorted(Comparator.comparingLong(RagFileMeta::getUploadedAt))
                .map(this::toFileDto)
                .toList();
        return new RagFilesDto(knowledgeBaseService.getMaxFiles(), files);
    }

    private void processTask(TaskState taskState, Path stagedFile, String originalName, String extension, long sizeBytes) {
        taskState.update(STATUS_RUNNING, STAGE_VALIDATING, 10, "文件校验通过，准备入队");
        try {
            taskState.update(STATUS_RUNNING, STAGE_QUEUE_UPDATING, 40, "正在写入知识库文件队列");
            RagEnqueueResult enqueueResult = knowledgeBaseService.enqueue(stagedFile, originalName, sizeBytes, extension);
            taskState.setUploadedFile(toFileDto(enqueueResult.uploadedFile()));
            taskState.setReplacedFile(toFileDto(enqueueResult.replacedFile()));

            taskState.update(STATUS_RUNNING, STAGE_REINDEXING, 70, "文件已写入，开始重建检索索引");
            RagRebuildResult rebuildResult = ragIndexService.rebuild(enqueueResult.files(), knowledgeBaseService.getDocumentDir());

            String message = "索引重建完成，已索引文档 " + rebuildResult.indexedDocumentCount() + " 个";
            taskState.update(STATUS_SUCCESS, STAGE_DONE, 100, message);
        } catch (Exception exception) {
            log.error("RAG upload task failed. taskId={}", taskState.getTaskId(), exception);
            String message = Optional.ofNullable(exception.getMessage()).orElse("未知错误");
            taskState.update(STATUS_FAILED, STAGE_FAILED, 100, "处理失败: " + message);
            try {
                Files.deleteIfExists(stagedFile);
            } catch (IOException ignored) {
                // ignore cleanup failure
            }
        } finally {
            trimTaskStore();
        }
    }

    private void validateUpload(MultipartFile file, String extension) {
        if (!StringUtils.hasText(extension) || !allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException("仅支持以下文件类型: " + String.join(", ", new ArrayList<>(allowedExtensions)));
        }
        if (file.getSize() > maxFileBytes) {
            throw new IllegalArgumentException("文件大小不能超过 " + ragProperties.getMaxFileSizeMb() + "MB");
        }
    }

    private String sanitizeOriginalName(String rawName) {
        String value = StringUtils.hasText(rawName) ? rawName : "upload-file";
        String cleaned;
        try {
            cleaned = Paths.get(value).getFileName().toString();
        } catch (InvalidPathException exception) {
            cleaned = "upload-file";
        }
        if (!StringUtils.hasText(cleaned)) {
            return "upload-file";
        }
        return cleaned.replaceAll("[\\r\\n]", "_");
    }

    private String extractExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private void trimTaskStore() {
        while (taskOrder.size() > MAX_TASKS) {
            String oldest = taskOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            TaskState state = taskStore.get(oldest);
            if (state == null) {
                continue;
            }
            String status = state.getStatus();
            if (STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status)) {
                taskStore.remove(oldest);
            } else {
                taskOrder.addLast(oldest);
                return;
            }
        }
    }

    private void deleteStagedFile(Path stagedFile) {
        if (stagedFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedFile);
        } catch (IOException ignored) {
            // ignore cleanup failure
        }
    }

    private RagFileDto toFileDto(RagFileMeta fileMeta) {
        if (fileMeta == null) {
            return null;
        }
        return new RagFileDto(
                fileMeta.getId(),
                fileMeta.getOriginalName(),
                fileMeta.getExtension(),
                fileMeta.getSizeBytes(),
                fileMeta.getUploadedAt()
        );
    }

    private static final class TaskState {
        private final String taskId;
        private final long createdAt;
        private volatile String status;
        private volatile String stage;
        private volatile int progress;
        private volatile String message;
        private volatile RagFileDto uploadedFile;
        private volatile RagFileDto replacedFile;
        private volatile long updatedAt;

        private TaskState(String taskId) {
            this.taskId = taskId;
            this.createdAt = System.currentTimeMillis();
            this.status = STATUS_PENDING;
            this.stage = STAGE_VALIDATING;
            this.progress = 0;
            this.message = "任务已创建，等待执行";
            this.updatedAt = this.createdAt;
        }

        private synchronized void update(String status, String stage, int progress, String message) {
            this.status = status;
            this.stage = stage;
            this.progress = progress;
            this.message = message;
            this.updatedAt = System.currentTimeMillis();
        }

        private synchronized void setUploadedFile(RagFileDto uploadedFile) {
            this.uploadedFile = uploadedFile;
            this.updatedAt = System.currentTimeMillis();
        }

        private synchronized void setReplacedFile(RagFileDto replacedFile) {
            this.replacedFile = replacedFile;
            this.updatedAt = System.currentTimeMillis();
        }

        private synchronized RagTaskDto toDto() {
            return new RagTaskDto(
                    taskId,
                    status,
                    stage,
                    progress,
                    message,
                    uploadedFile,
                    replacedFile,
                    createdAt,
                    updatedAt
            );
        }

        private String getTaskId() {
            return taskId;
        }

        private String getStatus() {
            return status;
        }
    }
}
