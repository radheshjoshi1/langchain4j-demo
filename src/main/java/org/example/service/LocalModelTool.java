package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import org.example.util.LocalModelUnavailableException;

/**
 * Exposes the embedded, in-process Jlama model (see {@link AgentFactory#createEmbeddedAgent()})
 * as a tool the main (hosted) agent can call, rather than driving the whole conversation with it.
 * This is what lets a step in the agent's tool-calling loop delegate a sub-question to a model
 * running entirely on this machine.
 *
 * <p>The underlying {@link JlamaChatModel} is loaded lazily on first use, not in the constructor:
 * an agent that never invokes this tool never pays the model-load cost.
 */
public class LocalModelTool {

    private volatile ChatModel localModel;

    @Tool("Delegates a question to an LLM running locally on this machine, with no external "
            + "network call. Useful for offline reasoning, drafting, or rephrasing without "
            + "depending on the hosted API.")
    public String askLocalModel(String query) {
        System.out.println("\n[SYSTEM NOTICE]: LLM is executing 'askLocalModel' tool (embedded, in-process) for query: " + query);
        try {
            return localChatModel().chat(query);
        } catch (RuntimeException e) {
            // localChatModel() below already throws LocalModelUnavailableException for load
            // failures; a plain RuntimeException here is an inference-time failure from
            // ChatModel.chat() instead (Jlama wraps its own IOException/generation failures
            // into dev.langchain4j.exception.* - see JlamaExceptionMapper - which is itself a
            // RuntimeException). Normalize both to the same typed exception before resolving,
            // so there is exactly one place that decides what "the local model failed" means.
            LocalModelUnavailableException failure = e instanceof LocalModelUnavailableException lmue
                    ? lmue
                    : new LocalModelUnavailableException(
                            "Embedded local model call failed for query '" + query + "'", e);
            return resolve(failure);
        }
    }

    /**
     * Single resolution point for local-model-tool failures: log the real cause for
     * diagnosis, then hand the hosted agent a plain-text fallback so the chat turn still
     * completes instead of erroring out on Main's onError handler. Swap in retry, a circuit
     * breaker, or falling back to a different model here as needed - this is the place.
     */
    private String resolve(LocalModelUnavailableException failure) {
        System.err.println("[LocalModelTool]: " + failure.getMessage() + " - cause: " + failure.getCause());
        return "The local model is unavailable right now (" + failure.getCause().getMessage()
                + "). Answer without it.";
    }

    private ChatModel localChatModel() {
        ChatModel model = localModel;
        if (model == null) {
            synchronized (this) {
                model = localModel;
                if (model == null) {
                    try {
                        model = JlamaChatModel.builder()
                                .modelName(AgentFactory.EMBEDDED_MODEL_NAME)
                                .modelCachePath(AgentFactory.JLAMA_MODEL_CACHE_PATH)
                                .build();
                    } catch (RuntimeException e) {
                        throw new LocalModelUnavailableException(
                                "Failed to load embedded model '" + AgentFactory.EMBEDDED_MODEL_NAME + "'", e);
                    }
                    localModel = model;
                }
            }
        }
        return model;
    }
}
