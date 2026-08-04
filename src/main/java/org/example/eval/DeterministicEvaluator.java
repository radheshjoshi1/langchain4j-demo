package org.example.eval;

import org.example.models.EvaluationScore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zero-cost, rule-based evaluator. Produces deterministic scores without any external LLM calls,
 * so re-running the same dataset run always yields the same score IDs (idempotent upserts in Langfuse).
 */
public class DeterministicEvaluator {

    public List<EvaluationScore> evaluate(String runName, String itemId, String expectedOutput,
                                           String actualOutput, Duration elapsed, Duration latencyBudget) {
        List<EvaluationScore> scores = new ArrayList<>();

        boolean executionSuccess = actualOutput != null && !actualOutput.isBlank();
        scores.add(new EvaluationScore(
                scoreId(runName, itemId, "execution_success"),
                "execution_success",
                executionSuccess ? 1 : 0,
                "BOOLEAN",
                executionSuccess ? "Agent produced a non-empty response." : "Agent response was null or blank."
        ));

        boolean withinBudget = elapsed.compareTo(latencyBudget) <= 0;
        scores.add(new EvaluationScore(
                scoreId(runName, itemId, "latency_within_budget"),
                "latency_within_budget",
                withinBudget ? 1 : 0,
                "BOOLEAN",
                "Elapsed: %d ms; Budget: %d ms".formatted(elapsed.toMillis(), latencyBudget.toMillis())
        ));

        if (expectedOutput != null && !expectedOutput.isBlank()) {
            boolean intentMatch = actualOutput != null
                    && actualOutput.toLowerCase().contains(expectedOutput.toLowerCase());
            scores.add(new EvaluationScore(
                    scoreId(runName, itemId, "intent_match"),
                    "intent_match",
                    intentMatch ? 1 : 0,
                    "BOOLEAN",
                    intentMatch
                            ? "Actual output contains the expected output substring."
                            : "Actual output does not contain the expected output substring."
            ));
        }

        return scores;
    }

    public static String scoreId(String runName, String itemId, String metricName) {
        return UUID.nameUUIDFromBytes((runName + ":" + itemId + ":" + metricName).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
