package org.example.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.example.config.LangfuseConfig;
import org.example.observability.LangfuseOtelListener;

import java.util.List;

/**
 * Factory class to instantiate the LLM Support Agent.
 */
public final class AgentFactory {

    private static final String MODEL_NAME = "gpt-4o-mini";

    private AgentFactory() {
    }

    /**
     * Builds and configures the StreamingSupportAgent.
     *
     * @return the configured StreamingSupportAgent
     */
    public static StreamingSupportAgent createAgent() {
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(MODEL_NAME)
                .listeners(List.of(new LangfuseOtelListener(MODEL_NAME, LangfuseConfig.traceName())))
                .build();

        return AiServices.builder(StreamingSupportAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new PaymentService(), new AccountService())
                .build();
    }
}
