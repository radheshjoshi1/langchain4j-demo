package org.example.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.example.config.LangfuseConfig;
import org.example.eval.judge.LlmJudgeEvaluator;
import org.example.models.ChatMessage;
import org.example.models.DatasetItem;
import org.example.models.DatasetResponse;
import org.example.models.EvaluationScore;
import org.example.service.StreamingSupportAgent;
import org.example.util.HttpUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatasetItemRunner {

    private static final Duration LATENCY_BUDGET = Duration.ofSeconds(15);

    private final StreamingSupportAgent supportAgent;
    private final DeterministicEvaluator evaluator;
    private final LlmJudgeEvaluator llmJudge;
    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("langchain4j-demo-eval", "1.0.0");

    public DatasetItemRunner(StreamingSupportAgent supportAgent) {
        this.supportAgent = supportAgent;
        this.evaluator = new DeterministicEvaluator();
        this.llmJudge = new LlmJudgeEvaluator();
        this.httpUtil = new HttpUtil();
    }

    /**
     * Runs evaluation over all items in a dataset, grouping every item's trace under a single
     * Langfuse session so the whole run can be viewed as one session in the dashboard.
     *
     * @return list of {@link RunResult} — one per item — for programmatic inspection (e.g. in tests)
     */
    public List<RunResult> runDataset(DatasetResponse datasetResponse, String runName) {
        List<DatasetItem> items = datasetResponse.getData();
        if (items == null || items.isEmpty()) {
            System.out.println("[Eval]: No items found in dataset.");
            return List.of();
        }

        System.out.printf("[Eval]: Starting dataset run '%s' with %d items...%n", runName, items.size());

        // One session ID for the whole run: every item's trace links to it so Langfuse groups
        // them as a single session instead of leaving each item's trace unrelated to the others.
        String sessionId = "session-" + UUID.randomUUID();

        List<RunResult> results = new ArrayList<>();
        int count = 1;
        for (DatasetItem item : items) {
            System.out.printf("%n--- Running Item %d/%d (ID: %s) ---%n", count++, items.size(), item.getId());
            results.add(run(item, runName, sessionId));
        }

        System.out.println("\n[Eval]: Dataset run completed.");
        return results;
    }

    /**
     * Runs a single dataset item through the agent, scores the output, uploads all scores as
     * OTel span attributes on the root {@code dataset-item-run} span, and posts the dataset
     * run-item link to Langfuse via REST.
     *
     * <p>Scores are set as span attributes using the pattern:
     * <pre>
     *   langfuse.score.&lt;scoreName&gt;           = String value (e.g. "1", "0", "0.85")
     *   langfuse.score.&lt;scoreName&gt;.comment    = rationale text
     *   langfuse.score.&lt;scoreName&gt;.dataType   = "BOOLEAN" or "NUMERIC"
     * </pre>
     * Langfuse ingests these attributes from the OTLP trace and surfaces them as scores on the
     * trace, without requiring a separate REST call to {@code /api/public/scores}.
     *
     * @return {@link RunResult} containing the item ID and all scores produced for this item
     */
    public RunResult run(DatasetItem item, String runName, String sessionId) {
        String itemId = item.getId();
        Instant start = Instant.now();

        // Root span for this item's turn. Making it current for the whole agent call means
        // LangfuseOtelListener's per-LLM-call spans are parented under it and share its trace ID.
        Span itemSpan = tracer.spanBuilder("dataset-item-run").startSpan();
        // Langfuse reads trace-level attributes from the root span only. Since this span - not
        // LangfuseOtelListener's child span - is now the root, the trace name/environment/tags
        // have to be set here or they silently fall back to the raw span name "dataset-item-run".
        itemSpan.setAttribute("langfuse.trace.name", LangfuseConfig.traceName());
        if (sessionId != null && !sessionId.isBlank()) {
            itemSpan.setAttribute("langfuse.session.id", sessionId);
        }
        itemSpan.setAttribute("langfuse.trace.environment", "production");
        itemSpan.setAttribute("langfuse.trace.tags", "banking,customer-support");
        String traceId = itemSpan.getSpanContext().getTraceId();

        List<EvaluationScore> collectedScores = new ArrayList<>();

        // Scope is unused by name - its only job is to be closed by try-with-resources, which
        // pops itemSpan off the OTel context so LangfuseOtelListener's child spans parent under it.
        try (Scope ignored = itemSpan.makeCurrent()) {
            String userPrompt = extractLatestUserPrompt(item.getInput());
            if (userPrompt == null) {
                System.out.println("[Eval]: Skipped item (No user prompt found).");
                return new RunResult(itemId, List.of());
            }

            // Langfuse derives the trace/run-level input from the root observation's input
            // attribute, so it has to be set here on itemSpan (not just on the child
            // LangfuseOtelListener generation span) or the dataset run list shows it blank.
            itemSpan.setAttribute("langfuse.observation.input", userPrompt);

            System.out.println("[Prompt]: " + userPrompt);
            System.out.print("[Agent Response]: ");

            CompletableFuture<String> futureResponse = new CompletableFuture<>();
            StringBuilder actualOutputBuilder = new StringBuilder();

            supportAgent.chat(userPrompt)
                    .onPartialResponse(token -> {
                        actualOutputBuilder.append(token);
                        System.out.print(token);
                        System.out.flush();
                    })
                    .onCompleteResponse(response -> {
                        System.out.println(); // New line after streaming completes
                        futureResponse.complete(actualOutputBuilder.toString());
                    })
                    .onError(error -> {
                        System.out.println();
                        System.err.println("[Eval]: Error during agent response: " + error.getMessage());
                        futureResponse.completeExceptionally(error);
                    })
                    .start();

            // Block until the full streamed response has arrived before scoring/posting.
            String actualOutput = futureResponse.join();
            Duration elapsed = Duration.between(start, Instant.now());

            // Same reasoning as the input attribute above: trace/run-level output comes from
            // the root observation, so it must be set on itemSpan.
            itemSpan.setAttribute("langfuse.observation.output", actualOutput);

            postDatasetRunOutput(runName, itemId, traceId);

            Object expectedOutputRaw = item.getExpectedOutput();
            String expectedOutput = expectedOutputRaw != null ? expectedOutputRaw.toString() : null;

            // Compute deterministic scores and upload via OTel attributes
            List<EvaluationScore> deterministicScores = evaluator.evaluate(
                    runName, itemId, expectedOutput, actualOutput, elapsed, LATENCY_BUDGET);
            for (EvaluationScore score : deterministicScores) {
                setScoreAttribute(itemSpan, score);
                collectedScores.add(score);
            }

            // Compute LLM-judge score and upload via OTel attributes
            EvaluationScore judgeScore = llmJudge.evaluate(runName, itemId, userPrompt, expectedOutput, actualOutput);
            setScoreAttribute(itemSpan, judgeScore);
            collectedScores.add(judgeScore);

        } catch (Exception e) {
            System.err.println("[Eval]: Failed turn execution for item ID: " + itemId + " - " + e.getMessage());
            itemSpan.recordException(e);
            // Post a failure score via OTel attribute so the failure is visible on the trace
            EvaluationScore failureScore = new EvaluationScore(
                    DeterministicEvaluator.scoreId(runName, itemId, "execution_success"),
                    "execution_success",
                    0,
                    "BOOLEAN",
                    "Exception: " + e.getMessage()
            );
            setScoreAttribute(itemSpan, failureScore);
            collectedScores.add(failureScore);
        } finally {
            itemSpan.end();
        }

        return new RunResult(itemId, collectedScores);
    }

    /**
     * Uploads a single evaluation score by setting OTel span attributes on {@code span}.
     *
     * <p>Langfuse reads the following attribute keys from the exported OTLP span and ingests
     * them as scores on the corresponding trace — no separate REST call to
     * {@code /api/public/scores} required:
     * <ul>
     *   <li>{@code langfuse.score.<name>} — the numeric or boolean value as a string</li>
     *   <li>{@code langfuse.score.<name>.comment} — optional rationale (truncated to 1000 chars)</li>
     *   <li>{@code langfuse.score.<name>.dataType} — {@code "BOOLEAN"} or {@code "NUMERIC"}</li>
     * </ul>
     */
    private void setScoreAttribute(Span span, EvaluationScore score) {
        String prefix = "langfuse.score." + score.name();
        span.setAttribute(prefix, String.valueOf(score.value()));
        span.setAttribute(prefix + ".dataType", score.dataType());
        if (score.comment() != null && !score.comment().isBlank()) {
            String comment = score.comment();
            span.setAttribute(prefix + ".comment",
                    comment.length() > 1000 ? comment.substring(0, 1000) : comment);
        }
        System.out.printf("[OTel Score Attribute]: %s = %s (%s)%n",
                score.name(), score.value(), score.dataType());
    }

    private String extractLatestUserPrompt(List<ChatMessage> inputMessages) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return null;
        }

        // Iterate backwards to find the last user turn
        for (int i = inputMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = inputMessages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    public DatasetResponse fetchDatasetItems(String datasetName) {
        String url = LangfuseConfig.baseUrl() + "/api/public/dataset-items?datasetName=" + datasetName;
        String response = httpUtil.sendGetRequest(url);

        try {
            return objectMapper.readValue(response, DatasetResponse.class);
        } catch (JsonProcessingException e) {
            System.err.println("[Eval]: Failed to parse dataset items: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void postDatasetRunOutput(String runName, String datasetItemId, String traceId) {
        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/dataset-run-items";

            Map<String, Object> body = new HashMap<>();
            body.put("runName", runName);
            body.put("datasetItemId", datasetItemId);

            // Only attach traceId if one exists for this evaluation execution
            if (traceId != null && !traceId.isEmpty()) {
                body.put("traceId", traceId);
            }

            String jsonPayload = objectMapper.writeValueAsString(body);
            httpUtil.sendPostRequest(url, jsonPayload);
            System.out.println("[Langfuse API]: Dataset run item posted successfully.");
        } catch (Exception e) {
            System.err.println("[Langfuse API]: Exception logging dataset run output: " + e.getMessage());
        }
    }
}
