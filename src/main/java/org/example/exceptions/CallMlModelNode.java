package org.example.exceptions;

import org.bsc.langgraph4j.action.NodeAction;
import org.example.ml.LocalMlModelClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the embedded {@link LocalMlModelClient} to classify the fetched exception context - all
 * in-process, no network call. On inference failure, fails safe: records a workflow error instead
 * of throwing, so {@link ExceptionGraphRouting} sends the transaction to manual review rather
 * than the graph crashing or silently auto-resolving.
 */
public class CallMlModelNode implements NodeAction<ExceptionAgentState> {

    private final LocalMlModelClient mlModelClient;

    public CallMlModelNode(LocalMlModelClient mlModelClient) {
        this.mlModelClient = mlModelClient;
    }

    @Override
    public Map<String, Object> apply(ExceptionAgentState state) {
        ExceptionContext context = state.exceptionContext()
                .orElseThrow(() -> new IllegalStateException("callMlModel reached with no exceptionContext in state"));

        Map<String, Object> update = new HashMap<>();
        try {
            update.put(ExceptionAgentState.ML_RESULT, mlModelClient.predict(context));
        } catch (RuntimeException e) {
            System.err.println("[CallMlModelNode]: local ML inference failed for " + context.transactionId()
                    + " - " + e.getMessage());
            update.put(ExceptionAgentState.WORKFLOW_ERRORS,
                    List.of("ML_MODEL_INFERENCE_FAILED: " + e.getMessage()));
        }
        return update;
    }
}
