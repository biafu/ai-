package com.yh.aicodehelper2.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.aicodehelper2.rag.model.RagEnqueueResult;
import com.yh.aicodehelper2.rag.model.RagFileMeta;
import com.yh.aicodehelper2.rag.model.RagQueueState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class RagKnowledgeBaseService {
    private static final String QUEUE_FILE_NAME = ".queue.json";
    private static final String TEMP_DIR_NAME = ".tmp";

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final ReentrantLock queueLock = new ReentrantLock();
    private final List<RagFileMeta> queue = new ArrayList<>();

    private Path documentDir;
    private Path queueFile;
    private Path tempDir;

    public RagKnowledgeBaseService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        this.documentDir = Paths.get(ragProperties.getDocumentDir()).toAbsolutePath().normalize();
        this.queueFile = documentDir.resolve(QUEUE_FILE_NAME);
        this.tempDir = documentDir.resolve(TEMP_DIR_NAME);
        Files.createDirectories(documentDir);
        Files.createDirectories(tempDir);

        queueLock.lock();
        try {
            loadQueueFromDiskUnlocked();
        } finally {
            queueLock.unlock();
        }
        log.info("RAG knowledge directory ready: {}", documentDir);
    }

    public int getMaxFiles() {
        return ragProperties.getMaxFiles();
    }

    public Path getDocumentDir() {
        return documentDir;
    }

    public Path createTempFile(String suffix) throws IOException {
        Files.createDirectories(tempDir);
        String tempSuffix = suffix.startsWith(".") ? suffix : "." + suffix;
        return Files.createTempFile(tempDir, "upload_", tempSuffix);
    }

    public List<RagFileMeta> listFiles() {
        queueLock.lock();
        try {
            return queue.stream().map(this::copyMeta).collect(Collectors.toList());
        } finally {
            queueLock.unlock();
        }
    }

    public RagEnqueueResult enqueue(Path stagedFile, String originalName, long sizeBytes, String extension) throws IOException {
        queueLock.lock();
        try {
            String normalizedExtension = extension.toLowerCase(Locale.ROOT);
            String safeOriginalName = sanitizeOriginalName(originalName);
            String storedName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "")
                    + "." + normalizedExtension;
            Path target = documentDir.resolve(storedName);

            Files.move(stagedFile, target, StandardCopyOption.REPLACE_EXISTING);

            RagFileMeta replaced = null;
            if (queue.size() >= ragProperties.getMaxFiles()) {
                replaced = queue.remove(0);
                Path replacedPath = documentDir.resolve(replaced.getStoredName());
                Files.deleteIfExists(replacedPath);
            }

            RagFileMeta uploaded = new RagFileMeta(
                    UUID.randomUUID().toString().replace("-", ""),
                    safeOriginalName,
                    storedName,
                    normalizedExtension,
                    sizeBytes,
                    System.currentTimeMillis()
            );
            queue.add(uploaded);
            persistQueueUnlocked();

            return new RagEnqueueResult(
                    copyMeta(uploaded),
                    replaced == null ? null : copyMeta(replaced),
                    queue.stream().map(this::copyMeta).collect(Collectors.toList())
            );
        } finally {
            queueLock.unlock();
        }
    }

    private void loadQueueFromDiskUnlocked() throws IOException {
        queue.clear();
        RagQueueState persisted = new RagQueueState(new ArrayList<>());
        if (Files.exists(queueFile)) {
            try {
                persisted = objectMapper.readValue(queueFile.toFile(), RagQueueState.class);
            } catch (Exception exception) {
                log.warn("Failed to parse queue metadata, rebuilding from files. file={}", queueFile, exception);
                persisted = new RagQueueState(new ArrayList<>());
            }
            if (persisted.getFiles() == null) {
                persisted.setFiles(new ArrayList<>());
            }
        }

        Map<String, RagFileMeta> orderedByStoredName = new LinkedHashMap<>();
        for (RagFileMeta item : persisted.getFiles()) {
            if (item == null || !StringUtils.hasText(item.getStoredName())) {
                continue;
            }
            Path path = documentDir.resolve(item.getStoredName());
            if (!Files.isRegularFile(path)) {
                continue;
            }
            item.setExtension(normalizeExtension(item.getExtension(), item.getStoredName()));
            item.setOriginalName(sanitizeOriginalName(item.getOriginalName()));
            if (!StringUtils.hasText(item.getId())) {
                item.setId(UUID.randomUUID().toString().replace("-", ""));
            }
            if (item.getUploadedAt() <= 0) {
                item.setUploadedAt(System.currentTimeMillis());
            }
            if (item.getSizeBytes() <= 0) {
                item.setSizeBytes(Files.size(path));
            }
            orderedByStoredName.put(item.getStoredName(), item);
        }

        List<Path> diskFiles = scanDiskFilesUnlocked();
        for (Path diskFile : diskFiles) {
            String storedName = diskFile.getFileName().toString();
            if (orderedByStoredName.containsKey(storedName)) {
                continue;
            }
            String extension = extensionFromFileName(storedName);
            long uploadedAt = Files.getLastModifiedTime(diskFile).toMillis();
            long sizeBytes = Files.size(diskFile);
            RagFileMeta discovered = new RagFileMeta(
                    UUID.randomUUID().toString().replace("-", ""),
                    storedName,
                    storedName,
                    extension,
                    sizeBytes,
                    uploadedAt
            );
            orderedByStoredName.put(storedName, discovered);
        }

        queue.addAll(orderedByStoredName.values().stream()
                .sorted(Comparator.comparingLong(RagFileMeta::getUploadedAt))
                .collect(Collectors.toList()));

        while (queue.size() > ragProperties.getMaxFiles()) {
            RagFileMeta removed = queue.remove(0);
            Files.deleteIfExists(documentDir.resolve(removed.getStoredName()));
        }

        persistQueueUnlocked();
    }

    private List<Path> scanDiskFilesUnlocked() throws IOException {
        try (Stream<Path> stream = Files.list(documentDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !QUEUE_FILE_NAME.equals(path.getFileName().toString()))
                    .sorted(Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException exception) {
                            return 0L;
                        }
                    }))
                    .collect(Collectors.toList());
        }
    }

    private void persistQueueUnlocked() throws IOException {
        RagQueueState state = new RagQueueState(queue.stream().map(this::copyMeta).collect(Collectors.toList()));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(queueFile.toFile(), state);
    }

    private String sanitizeOriginalName(String originalName) {
        String fallback = "upload-file";
        String value = StringUtils.hasText(originalName) ? originalName : fallback;
        String cleaned;
        try {
            cleaned = Paths.get(value).getFileName().toString();
        } catch (InvalidPathException exception) {
            cleaned = fallback;
        }
        if (!StringUtils.hasText(cleaned)) {
            return fallback;
        }
        return cleaned.replaceAll("[\\r\\n]", "_");
    }

    private String normalizeExtension(String extension, String fileName) {
        if (StringUtils.hasText(extension)) {
            return extension.toLowerCase(Locale.ROOT);
        }
        return extensionFromFileName(fileName);
    }

    private String extensionFromFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private RagFileMeta copyMeta(RagFileMeta source) {
        return new RagFileMeta(
                source.getId(),
                source.getOriginalName(),
                source.getStoredName(),
                source.getExtension(),
                source.getSizeBytes(),
                source.getUploadedAt()
        );
    }
}
