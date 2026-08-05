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
import org.example.util.HttpStatusException;
import org.example.util.HttpUtil;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
     * Runs evaluation over all items in a dataset.
     */
    public void runDataset(DatasetResponse datasetResponse, String runName) {
        List<DatasetItem> items = datasetResponse.getData();
        if (items == null || items.isEmpty()) {
            System.out.println("[Eval]: No items found in dataset.");
            return;
        }

        System.out.printf("[Eval]: Starting dataset run '%s' with %d items...%n", runName, items.size());

        int count = 1;
        for (DatasetItem item : items) {
            System.out.printf("%n--- Running Item %d/%d (ID: %s) ---%n", count++, items.size(), item.getId());
            run(item, runName);
        }

        System.out.println("\n[Eval]: Dataset run completed.");
    }

    public void run(DatasetItem item, String runName) {
        String itemId = item.getId();
        Instant start = Instant.now();

        // Root span for this item's turn. Making it current for the whole agent call means
        // LangfuseOtelListener's per-LLM-call spans are parented under it and share its trace ID.
        // That live trace ID - not item.getSourceTraceId(), which points at whatever historical
        // trace the dataset item was originally captured from - is what we link the
        // dataset-run-item and scores to below, so they point at what this run actually produced.
        Span itemSpan = tracer.spanBuilder("dataset-item-run").startSpan();
        // Langfuse reads trace-level attributes from the root span only. Since this span - not
        // LangfuseOtelListener's child span - is now the root, the trace name/environment/tags
        // have to be set here or they silently fall back to the raw span name "dataset-item-run".
        itemSpan.setAttribute("langfuse.trace.name", LangfuseConfig.traceName());
        itemSpan.setAttribute("langfuse.trace.environment", "production");
        itemSpan.setAttribute("langfuse.trace.tags", "banking,customer-support");
        String traceId = itemSpan.getSpanContext().getTraceId();

        try (Scope scope = itemSpan.makeCurrent()) {
            String userPrompt = extractLatestUserPrompt(item.getInput());
            if (userPrompt == null) {
                System.out.println("[Eval]: Skipped item (No user prompt found).");
                return;
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

            List<EvaluationScore> scores = evaluator.evaluate(runName, itemId, expectedOutput, actualOutput, elapsed, LATENCY_BUDGET);
            for (EvaluationScore score : scores) {
                postScoreToLangfuse(traceId, score);
            }

            EvaluationScore judgeScore = llmJudge.evaluate(runName, itemId, userPrompt, expectedOutput, actualOutput);
            postScoreToLangfuse(traceId, judgeScore);

        } catch (Exception e) {
            System.err.println("[Eval]: Failed turn execution for item ID: " + itemId + " - " + e.getMessage());
            itemSpan.recordException(e);
            EvaluationScore failureScore = new EvaluationScore(
                    DeterministicEvaluator.scoreId(runName, itemId, "execution_success"),
                    "execution_success",
                    0,
                    "BOOLEAN",
                    "Exception: " + e.getMessage()
            );
            postScoreToLangfuse(traceId, failureScore);
        } finally {
            itemSpan.end();
        }
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

    private void postScoreToLangfuse(String traceId, EvaluationScore score) {
        if (traceId == null || traceId.isEmpty()) {
            System.out.println("[Langfuse Score Skipped]: No traceId for score " + score.name());
            return;
        }

        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/scores";

            Map<String, Object> body = new HashMap<>();
            body.put("id", score.id());
            body.put("traceId", traceId);
            body.put("name", score.name());
            body.put("value", score.value());
            body.put("dataType", score.dataType());
            body.put("comment", score.comment());

            String jsonPayload = objectMapper.writeValueAsString(body);
            httpUtil.sendPostRequest(url, jsonPayload);
            System.out.println("[Langfuse Score Posted]: " + score.name() + " = " + score.value());
        } catch (Exception e) {
            // Everything we know about the failure - so if this can't be root-caused from
            // Langfuse's side, this line alone has enough to go on: which score, which trace,
            // and (since HttpUtil now surfaces HTTP status + response body on non-2xx) why.
            System.err.println("[Langfuse API]: Failed to post score '" + score.name()
                    + "' (id=" + score.id() + ", traceId=" + traceId + ", value=" + score.value()
                    + ", dataType=" + score.dataType() + "): " + e.getMessage());

            // A 429 means the marker POST would just spend more of the same exhausted budget
            // and fail too - every item in the run showed exactly that during a rate-limited
            // run. Skip it here rather than pile onto the backlog; the rate limiter in HttpUtil
            // is what actually prevents this going forward.
            if (e instanceof HttpStatusException httpStatusException && httpStatusException.statusCode() == 429) {
                System.err.println("[Langfuse API]: Skipping failure marker for '" + score.name()
                        + "' - failure was a rate limit (429), not worth spending more quota on.");
                return;
            }
            postScoreFailureMarker(traceId, score, e);
        }
    }

    /**
     * Best-effort: when a real score fails to post, push a companion marker score so the failure
     * itself is visible on the trace in Langfuse, not just in this run's console output.
     */
    private void postScoreFailureMarker(String traceId, EvaluationScore failedScore, Exception cause) {
        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/scores";
            String markerId = UUID.nameUUIDFromBytes(
                    (failedScore.id() + ":post_failed").getBytes(StandardCharsets.UTF_8)).toString();
            String comment = "Failed to post '" + failedScore.name() + "': " + cause.getMessage();

            Map<String, Object> body = new HashMap<>();
            body.put("id", markerId);
            body.put("traceId", traceId);
            body.put("name", failedScore.name() + "_post_failed");
            body.put("value", 1);
            body.put("dataType", "BOOLEAN");
            body.put("comment", comment.length() > 500 ? comment.substring(0, 500) : comment);

            String jsonPayload = objectMapper.writeValueAsString(body);
            httpUtil.sendPostRequest(url, jsonPayload);
            System.err.println("[Langfuse API]: Posted failure marker for score '" + failedScore.name() + "'.");
        } catch (Exception e) {
            System.err.println("[Langfuse API]: Also failed to post failure marker for score '"
                    + failedScore.name() + "': " + e.getMessage());
        }
    }
}
