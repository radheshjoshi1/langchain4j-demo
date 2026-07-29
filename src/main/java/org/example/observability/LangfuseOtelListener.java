package org.example.observability;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.List;

/**
 * OpenTelemetry listener for chat models.
 */
public class LangfuseOtelListener implements ChatModelListener {

    private static final Object SPAN_KEY = "langfuse_span";
    private static final Object SCOPE_KEY = "langfuse_scope";

    private final Tracer tracer;
    private final String modelName;
    private final String traceName;

    /**
     * Constructs a listener with the given model name.
     *
     * @param modelName the model name
     */
    public LangfuseOtelListener(String modelName) {
        this(modelName, null);
    }

    /**
     * Constructs a listener with the given model name and trace name.
     *
     * @param modelName the model name
     * @param traceName the trace name
     */
    public LangfuseOtelListener(String modelName, String traceName) {
        this.tracer = GlobalOpenTelemetry.getTracer("langchain4j", "1.0.0");
        this.modelName = modelName;
        this.traceName = traceName;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        Span span = tracer.spanBuilder("chat " + modelName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("gen_ai.system", "openai");
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.request.model", modelName);

        if (traceName != null && !traceName.isBlank()) {
            span.setAttribute("langfuse.trace.name", traceName);
        }

        List<ChatMessage> messages = ctx.chatRequest().messages();
        if (messages != null && !messages.isEmpty()) {
            span.addEvent("gen_ai.content.prompt",
                    Attributes.of(AttributeKey.stringKey("gen_ai.prompt"), messagesToJson(messages)));
        }

        ctx.attributes().put(SPAN_KEY, span);
        ctx.attributes().put(SCOPE_KEY, span.makeCurrent());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN_KEY);
        Scope scope = (Scope) ctx.attributes().get(SCOPE_KEY);
        if (span == null) {
            return;
        }

        var usage = ctx.chatResponse().tokenUsage();
        if (usage != null) {
            span.setAttribute("gen_ai.usage.input_tokens", usage.inputTokenCount());
            span.setAttribute("gen_ai.usage.output_tokens", usage.outputTokenCount());
        }

        var aiMessage = ctx.chatResponse().aiMessage();
        if (aiMessage != null && aiMessage.text() != null) {
            span.addEvent("gen_ai.content.completion",
                    Attributes.of(AttributeKey.stringKey("gen_ai.completion"), aiMessage.text()));
        }

        span.setStatus(StatusCode.OK);
        if (scope != null) {
            scope.close();
        }
        span.end();
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN_KEY);
        Scope scope = (Scope) ctx.attributes().get(SCOPE_KEY);
        if (span == null) {
            return;
        }

        span.setStatus(StatusCode.ERROR, ctx.error().getMessage());
        span.recordException(ctx.error());
        if (scope != null) {
            scope.close();
        }
        span.end();
    }

    private String messagesToJson(List<ChatMessage> messages) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            ChatMessage msg = messages.get(i);
            sb.append("{\"role\":\"").append(role(msg)).append("\",")
              .append("\"content\":\"").append(escapeJson(content(msg))).append("\"}");
        }
        return sb.append("]").toString();
    }

    private String role(ChatMessage msg) {
        return switch (msg.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            case CUSTOM -> "custom";
        };
    }

    private String content(ChatMessage msg) {
        return switch (msg.type()) {
            case SYSTEM -> ((SystemMessage) msg).text();
            case USER -> {
                UserMessage um = (UserMessage) msg;
                yield um.hasSingleText() ? um.singleText() : um.toString();
            }
            case AI -> {
                AiMessage am = (AiMessage) msg;
                yield am.text() != null ? am.text() : "[tool calls]";
            }
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) msg).text();
            case CUSTOM -> msg.toString();
        };
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
