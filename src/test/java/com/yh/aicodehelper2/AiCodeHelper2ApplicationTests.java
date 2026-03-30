package com.yh.aicodehelper2;

import com.yh.aicodehelper2.ai.AiCodeHelper;
import com.yh.aicodehelper2.ai.AiCodeHelperService;
import com.yh.aicodehelper2.ai.AiCodeHelperServiceFactory;
import com.yh.aicodehelper2.pojo.PeopleMessage;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiCodeHelper2ApplicationTests {

    @Resource
    AiCodeHelperService aiCodeHelperService;

    @Test

    void chat() {
        PeopleMessage result=aiCodeHelperService.chatPeopleMassage("你好请问现在美国气温多少度");
        System.out.println(result);
    }
}
