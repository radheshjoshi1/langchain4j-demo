package org.example.exceptions;

import org.example.ml.LocalMlModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExceptionGraphRoutingTest {

    @Test
    void workflowErrorAlwaysRoutesToManualReviewEvenWithABypassEligiblePrediction() {
        var state = new ExceptionAgentState(Map.of(
                ExceptionAgentState.WORKFLOW_ERRORS, List.of("boom"),
                ExceptionAgentState.ML_RESULT,
                new LocalMlModelClient.MlPrediction("AUTO_RESOLVE", true, 0.99, Map.of())));

        assertEquals(ExceptionGraphRouting.MANUAL_REVIEW, ExceptionGraphRouting.decideRoute(state));
    }

    @Test
    void bypassEligiblePredictionRoutesToAutoResolve() {
        var state = new ExceptionAgentState(Map.of(
                ExceptionAgentState.ML_RESULT,
                new LocalMlModelClient.MlPrediction("AUTO_RESOLVE", true, 0.9, Map.of())));

        assertEquals(ExceptionGraphRouting.AUTO_RESOLVE, ExceptionGraphRouting.decideRoute(state));
    }

    @Test
    void missingPredictionRoutesToManualReview() {
        var state = new ExceptionAgentState(Map.of());

        assertEquals(ExceptionGraphRouting.MANUAL_REVIEW, ExceptionGraphRouting.decideRoute(state));
    }
}
