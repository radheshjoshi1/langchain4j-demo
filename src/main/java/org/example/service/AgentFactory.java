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
        return createAgent(null);
    }

    /**
     * Builds and configures the StreamingSupportAgent, grouping every trace it produces
     * under the given Langfuse session ID.
     *
     * @param sessionId Langfuse session ID to tag traces with, or null to leave traces ungrouped
     * @return the configured StreamingSupportAgent
     */
    public static StreamingSupportAgent createAgent(String sessionId) {
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(MODEL_NAME)
                .maxCompletionTokens(50)
                .listeners(List.of(new LangfuseOtelListener(MODEL_NAME, LangfuseConfig.traceName(), sessionId)))
                .build();

        return AiServices.builder(StreamingSupportAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new PaymentService(), new AccountService())
                .build();
    }
}
