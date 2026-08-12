package org.example.eval;

import org.example.models.EvaluationScore;

import java.util.List;

/**
 * Captures the outcome of running a single dataset item through the evaluation pipeline.
 * Returned by {@link DatasetItemRunner#run} so callers (e.g. JUnit tests) can assert on scores
 * without having to inspect external systems like Langfuse.
 */
public record RunResult(
        /** The Langfuse dataset item ID this result belongs to. */
        String itemId,

        /** All scores computed for this item (deterministic + LLM-judge). Empty if the item was skipped. */
        List<EvaluationScore> scores
) {

    /**
     * Looks up a score by name, returning {@code null} if not found.
     */
    public EvaluationScore scoreByName(String name) {
        return scores.stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns {@code true} if a score with the given name exists and its value is {@code 1} (pass).
     */
    public boolean passed(String scoreName) {
        EvaluationScore score = scoreByName(scoreName);
        if (score == null) return false;
        Object val = score.value();
        if (val instanceof Number n) return n.intValue() == 1;
        return false;
    }
}
