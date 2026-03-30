package com.yh.aicodehelper2.rag;

import com.yh.aicodehelper2.rag.model.RagFileMeta;
import com.yh.aicodehelper2.rag.model.RagRebuildResult;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class RagIndexService implements ContentRetriever {
    private final EmbeddingModel qwenEmbeddingModel;
    private final AtomicReference<ContentRetriever> delegateRetriever = new AtomicReference<>(new EmptyContentRetriever());
    private final ReentrantLock rebuildLock = new ReentrantLock();

    public RagIndexService(EmbeddingModel qwenEmbeddingModel) {
        this.qwenEmbeddingModel = qwenEmbeddingModel;
    }

    public RagRebuildResult rebuild(List<RagFileMeta> files, Path baseDir) throws IOException {
        rebuildLock.lock();
        try {
            List<Document> documents = new ArrayList<>();
            for (RagFileMeta fileMeta : files) {
                if (fileMeta == null || !StringUtils.hasText(fileMeta.getStoredName())) {
                    continue;
                }
                Path filePath = baseDir.resolve(fileMeta.getStoredName()).normalize();
                if (!Files.isRegularFile(filePath)) {
                    continue;
                }
                String text = extractText(filePath, fileMeta.getExtension());
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                String documentText = "文件名: " + fileMeta.getOriginalName() + "\n" + text;
                documents.add(Document.from(documentText));
            }

            if (documents.isEmpty()) {
                delegateRetriever.set(new EmptyContentRetriever());
                return new RagRebuildResult(files.size(), 0);
            }

            EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
            DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(1000, 200);
            EmbeddingStoreIngestor embeddingStoreIngestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(qwenEmbeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();
            embeddingStoreIngestor.ingest(documents);

            ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(qwenEmbeddingModel)
                    .maxResults(5)
                    .minScore(0.7)
                    .build();

            delegateRetriever.set(retriever);
            return new RagRebuildResult(files.size(), documents.size());
        } finally {
            rebuildLock.unlock();
        }
    }

    @Override
    public List<Content> retrieve(Query query) {
        return delegateRetriever.get().retrieve(query);
    }

    private String extractText(Path filePath, String extension) throws IOException {
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return switch (normalizedExtension) {
            case "md", "txt" -> Files.readString(filePath, StandardCharsets.UTF_8);
            case "pdf" -> extractPdfText(filePath);
            case "docx" -> extractDocxText(filePath);
            default -> Files.readString(filePath, StandardCharsets.UTF_8);
        };
    }

    private String extractPdfText(Path filePath) throws IOException {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            PDFTextStripper textStripper = new PDFTextStripper();
            return textStripper.getText(document);
        }
    }

    private String extractDocxText(Path filePath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static final class EmptyContentRetriever implements ContentRetriever {
        @Override
        public List<Content> retrieve(Query query) {
            return Collections.emptyList();
        }
    }
}
