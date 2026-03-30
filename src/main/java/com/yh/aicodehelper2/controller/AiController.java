package com.yh.aicodehelper2.controller;

import com.yh.aicodehelper2.ai.AiCodeHelperService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Locale;

@RestController
@RequestMapping("/ai")
public class AiController {
    /**
     curl- G'http://localhost:8080/ai/chat'\
     --data-urlencode 'memoryId=1'\
     --data-urlencode 'message=你好'
     */

    @Resource(name = "aiCodeHelperService")
    private AiCodeHelperService aiCodeHelperService;

    @Resource(name = "aiCodeHelperServiceNoTools")
    private AiCodeHelperService aiCodeHelperServiceNoTools;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(int memoryId,
                                              String message,
                                              @RequestParam(defaultValue = "auto") String searchMode,
                                              @RequestParam(defaultValue = "general") String taskType) {
        SearchMode mode = SearchMode.from(searchMode);
        TaskType type = TaskType.from(taskType);
        String finalMessage = buildPrompt(message, mode, type);
        AiCodeHelperService targetService = mode == SearchMode.OFF ? aiCodeHelperServiceNoTools : aiCodeHelperService;
        return targetService.chatStream(memoryId, finalMessage)
                .map(chunk -> ServerSentEvent.<String>builder()
                .data(chunk)
                .build());

    }

    private String buildPrompt(String userMessage, SearchMode mode, TaskType taskType) {
        String normalizedUserMessage = userMessage == null ? "" : userMessage;
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                [ASSISTANT ROLE]
                You are a Chinese academic paper assistant.
                Keep answers evidence-based, structured, and practical.
                Never fabricate references, DOI, author names, or experimental results.

                [OUTPUT CONTRACT]
                Unless user specifies another format, output with these sections:
                1) 任务理解
                2) 核心回答
                3) 证据与依据（无证据时写“暂无可核验证据”）
                4) 可直接使用的内容
                5) 局限与风险
                6) 下一步建议
                """);

        if (mode == SearchMode.FORCE) {
            prompt.append("""
                    [SEARCH MODE]
                    You must call the web search tool first to collect up-to-date facts before answering.
                    If the tool is unavailable, clearly say so.
                    """);
        }
        if (mode == SearchMode.OFF) {
            prompt.append("""
                    [TOOL POLICY]
                    Do not use network search or any external tools.
                    Answer only with conversation context and built-in knowledge.
                    """);
        }

        prompt.append("\n[TASK TYPE]\n").append(taskType.instruction).append("\n");
        prompt.append("""
                [QUALITY RULES]
                - If evidence is insufficient, explicitly say "证据不足".
                - If user asks for references, output only verifiable references.
                - Keep language concise and academic; avoid vague filler.
                - For writing tasks, provide directly usable text blocks.
                - Avoid decorative symbols and heavy Markdown marks.
                """);
        prompt.append("\n[USER QUESTION]\n").append(normalizedUserMessage);
        return prompt.toString();
    }

    private enum SearchMode {
        AUTO,
        FORCE,
        OFF;

        private static SearchMode from(String raw) {
            if (raw == null) {
                return AUTO;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "force" -> FORCE;
                case "off" -> OFF;
                default -> AUTO;
            };
        }
    }

    private enum TaskType {
        GENERAL("general", """
                通用论文助手：优先给出结构化、可执行的学术建议。
                """),
        TOPIC("topic", """
                任务聚焦：论文选题与研究问题定义。
                输出应包含选题方向、研究空白、可行性与创新点。
                """),
        LITERATURE("literature", """
                任务聚焦：文献综述。
                输出应包含主题脉络、代表工作对比、不足与研究机会。
                """),
        METHOD("method", """
                任务聚焦：方法设计与技术路线。
                输出应包含方法步骤、关键参数、潜在失败点与替代方案。
                """),
        EXPERIMENT("experiment", """
                任务聚焦：实验设计与结果分析。
                输出应包含指标、对照组、消融实验和结果解释框架。
                """),
        POLISH("polish", """
                任务聚焦：学术写作润色。
                输出应提供“润色后文本”与“修改理由”。
                """);

        private final String code;
        private final String instruction;

        TaskType(String code, String instruction) {
            this.code = code;
            this.instruction = instruction;
        }

        private static TaskType from(String raw) {
            if (raw == null) {
                return GENERAL;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (TaskType type : values()) {
                if (type.code.equals(value)) {
                    return type;
                }
            }
            return GENERAL;
        }
    }
}
