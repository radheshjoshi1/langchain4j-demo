package org.example.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.example.config.LangfuseConfig;
import org.example.config.OpenTelemetryConfig;
import org.example.models.EvaluationScore;
import org.example.observability.LangfuseScoreReporter;
import org.example.util.HttpUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real agent (built via AgentFactory, backed by the public langchain4j.dev demo
 * OpenAI-compatible endpoint) through a set of banking and out-of-scope test cases, treating
 * each case as an experiment: does the agent stay within its banking support scope, and does it
 * still use its tools correctly for questions that are in scope. Each case's trace is scored
 * pass/fail in Langfuse (via {@link LangfuseScoreReporter}), the same way DatasetItemRunner
 * scores dataset runs, so results show up in the Langfuse dashboard's score list.
 *
 * These are integration tests - they make real network calls, so a fresh agent (and thus a
 * fresh chat memory) is created per test to keep test cases independent of one another.
 *
 * OpenTelemetry is normally only wired up to the Langfuse OTLP exporter by Main.main(), so
 * running these tests alone (mvn test) would otherwise leave every span on a no-op tracer and
 * nothing would reach Langfuse. Registering it here mirrors that startup step so `mvn test` is
 * self-sufficient - no need to run the application separately just to get traces/scores exported.
 */
class StreamingSupportAgentTest {

    private static OpenTelemetrySdk openTelemetry;
    private static String sessionId;
    private static LangfuseScoreReporter scoreReporter;

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("langchain4j-demo-test", "1.0.0");

    private StreamingSupportAgent agent;

    @BeforeAll
    static void initTelemetry() {
        openTelemetry = OpenTelemetryConfig.initOpenTelemetry();
        // One session for the whole test class, so all its traces group together in Langfuse
        // instead of appearing as unrelated traces - same reasoning as Main's console mode.
        sessionId = "session-test-" + UUID.randomUUID();
        scoreReporter = new LangfuseScoreReporter(new HttpUtil());
    }

    @AfterAll
    static void shutdownTelemetry() {
        openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
        openTelemetry.close();
    }

    @BeforeEach
    void setUp() {
        agent = AgentFactory.createAgent(sessionId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is 2+2?",
            "Who is the prime minister of India?",
            "What's the weather like today?"
    })
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void refusesQuestionsOutsideBankingScope(String offTopicQuestion) {
        TracedTurn turn = runTracedTurn("out-of-scope-question", offTopicQuestion);
        boolean pass = isRefusal(turn.response());

        scoreReporter.postScore(turn.traceId(), boolScore("scope_refusal_correct", pass,
                pass ? "Correctly refused an out-of-scope question."
                        : "Did not refuse: " + turn.response()));

        assertTrue(pass, "Expected an out-of-scope refusal for \"" + offTopicQuestion + "\" but got: " + turn.response());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void answersPaymentStatusQuestionsUsingTheTool() {
        TracedTurn turn = runTracedTurn("payment-status-question", "What is the status of transaction TXN_001?");
        boolean pass = !isRefusal(turn.response()) && turn.response().toLowerCase().contains("success");

        scoreReporter.postScore(turn.traceId(), boolScore("tool_answer_correct", pass,
                pass ? "checkPaymentStatus result surfaced correctly." : "Unexpected response: " + turn.response()));

        assertFalse(isRefusal(turn.response()), "In-scope payment question was refused: " + turn.response());
        assertTrue(turn.response().toLowerCase().contains("success"),
                "Expected the checkPaymentStatus tool result to surface in the answer: " + turn.response());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void answersAccountTierQuestionsUsingTheTool() {
        TracedTurn turn = runTracedTurn("account-tier-question", "What is the account tier for customer CUS_001?");
        boolean pass = !isRefusal(turn.response()) && turn.response().toLowerCase().contains("vip");

        scoreReporter.postScore(turn.traceId(), boolScore("tool_answer_correct", pass,
                pass ? "getAccountTier result surfaced correctly." : "Unexpected response: " + turn.response()));

        assertFalse(isRefusal(turn.response()), "In-scope account question was refused: " + turn.response());
        assertTrue(turn.response().toLowerCase().contains("vip"),
                "Expected the getAccountTier tool result to surface in the answer: " + turn.response());
    }

    private static boolean isRefusal(String response) {
        return response.toLowerCase().contains("can only help with questions about your banking");
    }

    private static EvaluationScore boolScore(String name, boolean pass, String comment) {
        return new EvaluationScore(UUID.randomUUID().toString(), name, pass ? 1 : 0, "BOOLEAN", comment);
    }

    /**
     * Runs one chat turn under its own root span so it gets its own Langfuse trace ID, mirroring
     * DatasetItemRunner's per-item span so a score can be attached to the trace afterward.
     */
    private TracedTurn runTracedTurn(String observationName, String message) {
        Span itemSpan = tracer.spanBuilder(observationName).startSpan();
        itemSpan.setAttribute("langfuse.trace.name", LangfuseConfig.traceName());
        itemSpan.setAttribute("langfuse.session.id", sessionId);
        itemSpan.setAttribute("langfuse.trace.environment", "test");
        itemSpan.setAttribute("langfuse.trace.tags", "banking,junit");
        String traceId = itemSpan.getSpanContext().getTraceId();

        try (Scope ignored = itemSpan.makeCurrent()) {
            itemSpan.setAttribute("langfuse.observation.input", message);
            String response = chatAndCollect(message);
            itemSpan.setAttribute("langfuse.observation.output", response);
            return new TracedTurn(traceId, response);
        } finally {
            itemSpan.end();
        }
    }

    private String chatAndCollect(String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        agent.chat(message)
                .onPartialResponse(output::append)
                .onCompleteResponse(response -> future.complete(output.toString()))
                .onError(future::completeExceptionally)
                .start();

        try {
            return future.get(25, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Agent call failed for message: " + message, e);
        }
    }

    private record TracedTurn(String traceId, String response) {
    }
}
