package org.example.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.LangfuseConfig;
import org.example.models.ChatMessage;
import org.example.models.DatasetItem;
import org.example.models.DatasetResponse;
import org.example.models.EvaluationScore;
import org.example.service.StreamingSupportAgent;
import org.example.util.HttpUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DatasetItemRunner {

    private static final Duration LATENCY_BUDGET = Duration.ofSeconds(15);

    private final StreamingSupportAgent supportAgent;
    private final DeterministicEvaluator evaluator;
    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatasetItemRunner(StreamingSupportAgent supportAgent) {
        this.supportAgent = supportAgent;
        this.evaluator = new DeterministicEvaluator();
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
        String traceId = item.getSourceTraceId();
        Instant start = Instant.now();

        try {
            String userPrompt = extractLatestUserPrompt(item.getInput());
            if (userPrompt == null) {
                System.out.println("[Eval]: Skipped item (No user prompt found).");
                return;
            }

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

            postDatasetRunOutput(runName, itemId, traceId);

            Object expectedOutputRaw = item.getExpectedOutput();
            String expectedOutput = expectedOutputRaw != null ? expectedOutputRaw.toString() : null;

            List<EvaluationScore> scores = evaluator.evaluate(runName, itemId, expectedOutput, actualOutput, elapsed, LATENCY_BUDGET);
            for (EvaluationScore score : scores) {
                postScoreToLangfuse(traceId, score);
            }

        } catch (Exception e) {
            System.err.println("[Eval]: Failed turn execution for item ID: " + itemId + " - " + e.getMessage());
            EvaluationScore failureScore = new EvaluationScore(
                    DeterministicEvaluator.scoreId(runName, itemId, "execution_success"),
                    "execution_success",
                    0,
                    "BOOLEAN",
                    "Exception: " + e.getMessage()
            );
            postScoreToLangfuse(traceId, failureScore);
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
            System.err.println("[Langfuse API]: Failed to post score: " + e.getMessage());
        }
    }
}
