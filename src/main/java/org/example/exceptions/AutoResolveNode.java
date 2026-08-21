package org.example.exceptions;

import org.bsc.langgraph4j.action.NodeAction;
import org.example.ml.LocalMlModelClient;

import java.util.Map;

/**
 * Terminal node reached when the classifier says this exception is safe to close without human
 * review.
 */
public class AutoResolveNode implements NodeAction<ExceptionAgentState> {

    @Override
    public Map<String, Object> apply(ExceptionAgentState state) {
        ExceptionContext context = state.exceptionContext().orElseThrow();
        LocalMlModelClient.MlPrediction prediction = state.mlResult().orElseThrow();

        String resolution = String.format(
                "AUTO-RESOLVED %s: score=%.3f (model=%s) - no manual review needed.",
                context.transactionId(), prediction.score(), prediction.raw().getOrDefault("modelVersion", "n/a"));
        return Map.of(ExceptionAgentState.RESOLUTION, resolution);
    }
}
