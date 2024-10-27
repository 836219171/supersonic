package com.tencent.supersonic.chat.server.processor.parse;

import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.enums.AppModule;
import com.tencent.supersonic.common.util.ChatAppManager;
import com.tencent.supersonic.headless.server.utils.ModelConfigHelper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.provider.ModelProvider;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ErrorMessageProcessor implements ParseResultProcessor {

    private static final Logger keyPipelineLog = LoggerFactory.getLogger("keyPipeline");

    public static final String APP_KEY_ERROR_MESSAGE = "REWRITE_ERROR_MESSAGE";
    private static final String REWRITE_ERROR_MESSAGE_INSTRUCTION = ""
            + "#Role: You are a data business partner who closely interacts with business people.\n"
            + "#Task: Your will be provided with user input, system output and some examples, "
            + "please respond shortly to teach user how to ask the right question, "
            + "by using `Examples` as references."
            + "#Rules: ALWAYS respond with the same language as the `Input`.\n"
            + "#Input: {{user_question}}\n" + "#Output: {{system_message}}\n"
            + "#Examples: {{examples}}\n" + "#Response: ";

    public ErrorMessageProcessor() {
        ChatAppManager.register(APP_KEY_ERROR_MESSAGE,
                ChatApp.builder().prompt(REWRITE_ERROR_MESSAGE_INSTRUCTION).name("异常提示改写")
                        .appModule(AppModule.CHAT).description("通过大模型将异常信息改写为更友好和引导性的提示用语")
                        .enable(false).build());
    }

    @Override
    public void process(ParseContext parseContext) {
        String errMsg = parseContext.getResponse().getErrorMsg();
        ChatApp chatApp = parseContext.getAgent().getChatAppConfig().get(APP_KEY_ERROR_MESSAGE);
        if (StringUtils.isBlank(errMsg) || Objects.isNull(chatApp) || !chatApp.isEnable()) {
            return;
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("user_question", parseContext.getRequest().getQueryText());
        variables.put("system_message", errMsg);

        StringBuilder exampleStr = new StringBuilder();
        parseContext.getResponse().getUsedExemplars().forEach(e -> exampleStr.append(
                String.format("<Question:{%s},Schema:{%s}> ", e.getQuestion(), e.getDbSchema())));
        parseContext.getAgent().getExamples()
                .forEach(e -> exampleStr.append(String.format("<Question:{%s}> ", e)));
        variables.put("examples", exampleStr);

        Prompt prompt = PromptTemplate.from(chatApp.getPrompt()).apply(variables);
        ChatLanguageModel chatLanguageModel =
                ModelProvider.getChatModel(ModelConfigHelper.getChatModelConfig(chatApp));
        Response<AiMessage> response = chatLanguageModel.generate(prompt.toUserMessage());
        String rewrittenMsg = response.content().text();
        parseContext.getResponse().setErrorMsg(rewrittenMsg);
        keyPipelineLog.info("ErrorMessageProcessor modelReq:\n{} \nmodelResp:\n{}", prompt.text(),
                rewrittenMsg);
    }

}