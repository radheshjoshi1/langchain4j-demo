package org.example.observability;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LangfuseOtelListener implements ChatModelListener {

    private static final String SPAN_KEY = "langfuse_span";

    // Guarantees span retrieval across async streaming threads
    private final Map<Object, Span> activeSpans = new ConcurrentHashMap<>();

    private final Tracer tracer;
    private final String modelName;
    private final String traceName;
    private final String sessionId;

    public LangfuseOtelListener(String modelName, String traceName) {
        this(modelName, traceName, null);
    }

    public LangfuseOtelListener(String modelName, String traceName, String sessionId) {
        this.tracer = GlobalOpenTelemetry.getTracer("langchain4j", "1.0.0");
        this.modelName = modelName;
        this.traceName = traceName;
        this.sessionId = sessionId;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        Span span = tracer.spanBuilder("chat " + modelName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        // Store in context map and fallback map
        ctx.attributes().put(SPAN_KEY, span);
        if (ctx.chatRequest() != null) {
            activeSpans.put(ctx.chatRequest(), span);
        }

        try {
            span.setAttribute("langfuse.observation.type", "generation");
            span.setAttribute("gen_ai.system", "openai");
            span.setAttribute("gen_ai.operation.name", "chat");
            span.setAttribute("gen_ai.request.model", modelName);

            if (traceName != null && !traceName.isBlank()) {
                span.setAttribute("langfuse.trace.name", traceName);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                span.setAttribute("langfuse.session.id", sessionId);
            }
            span.setAttribute("langfuse.trace.environment", "production");
            span.setAttribute("langfuse.trace.tags", "banking,customer-support");

            span.setAttribute("langfuse.observation.metadata.service_name", "banking-agent");
            span.setAttribute("langfuse.observation.metadata.intent", "check_transaction_status");

            // Set Input Prompt
            List<ChatMessage> messages = ctx.chatRequest() != null ? ctx.chatRequest().messages() : null;
            if (messages != null && !messages.isEmpty()) {
                String promptJson = safeMessagesToJson(messages);
                span.setAttribute("gen_ai.prompt", promptJson);
                span.setAttribute("langfuse.observation.input", promptJson);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        // Retrieve span from context or fallback map
        Span span = (Span) ctx.attributes().get(SPAN_KEY);
        if (span == null && ctx.chatRequest() != null) {
            span = activeSpans.remove(ctx.chatRequest());
        } else if (ctx.chatRequest() != null) {
            activeSpans.remove(ctx.chatRequest());
        }

        if (span == null) {
            return;
        }

        try {
            var response = ctx.chatResponse();
            if (response != null) {
                var aiMessage = response.aiMessage();
                if (aiMessage != null && aiMessage.text() != null) {
                    span.setAttribute("gen_ai.completion", aiMessage.text());
                    span.setAttribute("langfuse.observation.output", aiMessage.text());
                }
            }

            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
        } finally {
            span.end();
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN_KEY);
        if (span == null && ctx.chatRequest() != null) {
            span = activeSpans.remove(ctx.chatRequest());
        } else if (ctx.chatRequest() != null) {
            activeSpans.remove(ctx.chatRequest());
        }

        if (span == null) return;

        try {
            if (ctx.error() != null) {
                span.recordException(ctx.error());
                span.setStatus(StatusCode.ERROR, ctx.error().getMessage());
            }
        } finally {
            span.end();
        }
    }

    private String safeMessagesToJson(List<ChatMessage> messages) {
        try {
            var sb = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) sb.append(",");
                ChatMessage msg = messages.get(i);
                sb.append("{\"role\":\"").append(role(msg)).append("\",")
                        .append("\"content\":\"").append(escapeJson(safeContent(msg))).append("\"}");
            }
            return sb.append("]").toString();
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
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

    private String safeContent(ChatMessage msg) {
        if (msg == null) return "";
        try {
            return switch (msg.type()) {
                case SYSTEM -> ((SystemMessage) msg).text();
                case USER -> {
                    UserMessage um = (UserMessage) msg;
                    yield um.hasSingleText() ? um.singleText() : String.valueOf(um.contents());
                }
                case AI -> {
                    AiMessage am = (AiMessage) msg;
                    if (am.text() != null && !am.text().isBlank()) {
                        yield am.text();
                    } else if (am.hasToolExecutionRequests()) {
                        yield "[Tool Calls: " + am.toolExecutionRequests() + "]";
                    } else {
                        yield am.toString();
                    }
                }
                case TOOL_EXECUTION_RESULT -> {
                    ToolExecutionResultMessage tm = (ToolExecutionResultMessage) msg;
                    yield tm.text() != null ? tm.text() : "";
                }
                case CUSTOM -> msg.toString();
            };
        } catch (Exception e) {
            return msg.toString();
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}