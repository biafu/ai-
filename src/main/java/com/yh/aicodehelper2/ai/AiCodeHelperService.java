package com.yh.aicodehelper2.ai;


import com.yh.aicodehelper2.pojo.PeopleMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.Resource;
import reactor.core.publisher.Flux;


public interface AiCodeHelperService {

    @SystemMessage (fromResource = "system.txt")
    String chat(String message);

    @SystemMessage (fromResource = "system.txt")
    PeopleMessage chatPeopleMassage(String message);

    @SystemMessage (fromResource = "system.txt")
    Flux< String> chatStream(@MemoryId int memoryId, @UserMessage String message);
}
