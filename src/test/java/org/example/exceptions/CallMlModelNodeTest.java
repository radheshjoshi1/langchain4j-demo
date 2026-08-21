package org.example.exceptions;

import org.example.ml.LocalMlModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMlModelNodeTest {

    @Test
    void inferenceFailureRecordsWorkflowErrorInsteadOfThrowing() {
        LocalMlModelClient throwingClient = new LocalMlModelClient() {
            @Override
            public MlPrediction predict(ExceptionContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String modelVersion() {
                return "test";
            }
        };
        var node = new CallMlModelNode(throwingClient);
        var context = new ExceptionContext("TXN_002", "CUS_002", "FAILED", "Invalid HMAC Signature.", "STANDARD");
        var state = new ExceptionAgentState(Map.of(ExceptionAgentState.EXCEPTION_CONTEXT, context));

        Map<String, Object> update = node.apply(state);

        assertFalse(update.containsKey(ExceptionAgentState.ML_RESULT));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) update.get(ExceptionAgentState.WORKFLOW_ERRORS);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("boom"));
    }

    @Test
    void successfulPredictionIsStoredInMlResult() {
        LocalMlModelClient client = new LocalMlModelClient() {
            @Override
            public MlPrediction predict(ExceptionContext context) {
                return new MlPrediction("AUTO_RESOLVE", true, 0.9, Map.of());
            }

            @Override
            public String modelVersion() {
                return "test";
            }
        };
        var node = new CallMlModelNode(client);
        var context = new ExceptionContext("TXN_003", "CUS_001", "PENDING", "Awaiting gateway confirmation.", "VIP");
        var state = new ExceptionAgentState(Map.of(ExceptionAgentState.EXCEPTION_CONTEXT, context));

        Map<String, Object> update = node.apply(state);

        assertFalse(update.containsKey(ExceptionAgentState.WORKFLOW_ERRORS));
        var prediction = (LocalMlModelClient.MlPrediction) update.get(ExceptionAgentState.ML_RESULT);
        assertTrue(prediction.bypassEligible());
    }
}
