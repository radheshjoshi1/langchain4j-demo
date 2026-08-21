package org.example.exceptions;

import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Terminal node reached whenever the classifier says review is needed, its prediction is
 * missing, or classification itself failed (see {@link ExceptionGraphRouting}).
 */
public class ManualReviewNode implements NodeAction<ExceptionAgentState> {

    @Override
    public Map<String, Object> apply(ExceptionAgentState state) {
        ExceptionContext context = state.exceptionContext().orElseThrow();

        String reason = state.workflowErrors().isEmpty()
                ? state.mlResult()
                        .map(prediction -> String.format("score=%.3f below threshold", prediction.score()))
                        .orElse("no ML prediction available")
                : String.join("; ", state.workflowErrors());

        String resolution = String.format("ROUTED TO MANUAL REVIEW %s: %s", context.transactionId(), reason);
        return Map.of(ExceptionAgentState.RESOLUTION, resolution);
    }
}
