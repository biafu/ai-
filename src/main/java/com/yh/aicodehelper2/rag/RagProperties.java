package com.yh.aicodehelper2.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    /**
     * Document storage directory.
     * In development this can point to src/main/resources/doce.
     */
    private String documentDir = "src/main/resources/doce";

    /**
     * Max retained knowledge files.
     */
    private int maxFiles = 10;

    /**
     * Max single upload size in MB.
     */
    private long maxFileSizeMb = 20;

    /**
     * Allowed upload extensions.
     */
    private List<String> allowedExtensions = List.of("txt", "md", "pdf", "docx");
}
