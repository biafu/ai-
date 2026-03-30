package com.yh.aicodehelper2.ai;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AiCodeHelperServiceFactory {
    @Resource
    private ChatModel qwenChatModel;
    @Resource
    private ContentRetriever contentRetriever;
    @Resource
    private McpToolProvider mcpToolProvider;
    @Resource
    private StreamingChatModel streamingChatModel;

    @Bean(name = "aiCodeHelperService")
    public AiCodeHelperService createAiCodeHelperServiceWithTools() {
        return AiServices.builder(AiCodeHelperService.class)
                .chatModel(qwenChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .contentRetriever(contentRetriever)
                .toolProvider(mcpToolProvider)
                .build();
    }

    @Bean(name = "aiCodeHelperServiceNoTools")
    public AiCodeHelperService createAiCodeHelperServiceWithoutTools() {
        return AiServices.builder(AiCodeHelperService.class)
                .chatModel(qwenChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .contentRetriever(contentRetriever)
                .build();
    }



}
