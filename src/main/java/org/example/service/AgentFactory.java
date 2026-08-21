package org.example.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.jlama.JlamaStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.example.config.LangfuseConfig;
import org.example.observability.LangfuseOtelListener;

import java.nio.file.Path;
import java.util.List;

/**
 * Factory class to instantiate the LLM Support Agent.
 */
public final class AgentFactory {

    private static final String MODEL_NAME = "gpt-4o-mini";

    // POC: local model served by Ollama (`ollama serve`, model pulled via `ollama pull llama3.2:3b`)
    // - still a separate process the agent talks to over HTTP, just on localhost instead of a
    // hosted endpoint. Superseded by createEmbeddedAgent() below for the "no separate process at
    // all" requirement, but kept as a comparison point between the two approaches.
    private static final String LOCAL_MODEL_NAME = "llama3.2:3b";
    private static final String LOCAL_MODEL_BASE_URL = "http://localhost:11434";

    // POC: model weights loaded and run in-process (same JVM, same heap/threads as the agent)
    // via Jlama - no server, no HTTP call, nothing to keep running outside this process. Weights
    // are fetched from Hugging Face into JLAMA_MODEL_CACHE_PATH on first use and reused after
    // that; the reuse is a local file cache, not a runtime network dependency.
    // Package-private (not private): shared with LocalModelTool, which loads the same embedded
    // model lazily behind a @Tool method instead of driving the whole agent with it.
    static final String EMBEDDED_MODEL_NAME = "tjake/Llama-3.2-3B-Instruct-JQ4";
    static final Path JLAMA_MODEL_CACHE_PATH =
            Path.of(System.getProperty("user.home"), ".jlama", "models");

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
                .tools(new PaymentService(), new AccountService(), new LocalModelTool())
                .build();
    }

    /**
     * Builds the same agent, but backed by a model running locally via Ollama instead of the
     * hosted OpenAI-compatible endpoint. Requires {@code ollama serve} running with
     * {@code LOCAL_MODEL_NAME} pulled (e.g. {@code ollama pull llama3.2:1b}). No Langfuse/OTel
     * listener is attached here - this is a standalone POC path, not wired into the tracing
     * pipeline that assumes an OpenAI-shaped model.
     *
     * @return the configured StreamingSupportAgent running against the local Ollama model
     */
    public static StreamingSupportAgent createLocalAgent() {
        OllamaStreamingChatModel streamingChatModel = OllamaStreamingChatModel.builder()
                .baseUrl(LOCAL_MODEL_BASE_URL)
                .modelName(LOCAL_MODEL_NAME)
                .build();

        return AiServices.builder(StreamingSupportAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new PaymentService(), new AccountService())
                .build();
    }

    /**
     * Builds the same agent backed by a model embedded directly in this JVM via Jlama, rather
     * than one reached over HTTP (hosted API or a local server like Ollama). The JVM must be run
     * with {@code --add-modules jdk.incubator.vector} (Jlama's tensor math uses the Vector API
     * incubator module) - either pass it on the java command line, or export
     * {@code JDK_JAVA_OPTIONS="--add-modules jdk.incubator.vector"}. Requires Java 20+.
     * No Langfuse/OTel listener is attached, for the same reason as {@link #createLocalAgent()}.
     *
     * @return the configured StreamingSupportAgent running against the embedded Jlama model
     */
    public static StreamingSupportAgent createEmbeddedAgent() {
        JlamaStreamingChatModel streamingChatModel = JlamaStreamingChatModel.builder()
                .modelName(EMBEDDED_MODEL_NAME)
                .modelCachePath(JLAMA_MODEL_CACHE_PATH)
                .build();

        return AiServices.builder(StreamingSupportAgent.class)
                .streamingChatModel(streamingChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new PaymentService(), new AccountService())
                .build();
    }
}
