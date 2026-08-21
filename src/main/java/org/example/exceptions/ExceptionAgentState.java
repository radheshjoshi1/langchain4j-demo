package org.example.exceptions;

import org.bsc.langgraph4j.state.AgentState;
import org.example.ml.LocalMlModelClient.MlPrediction;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State threaded through the exception graph: the initial {@code transactionId}/{@code
 * customerId} input, the {@link ExceptionContext} fetched from it, the {@link MlPrediction} from
 * the embedded classifier, any workflow errors, and the final resolution text.
 */
public class ExceptionAgentState extends AgentState {

    public static final String TRANSACTION_ID = "transactionId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String EXCEPTION_CONTEXT = "exceptionContext";
    public static final String ML_RESULT = "mlResult";
    public static final String WORKFLOW_ERRORS = "workflowErrors";
    public static final String RESOLUTION = "resolution";

    public ExceptionAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public Optional<ExceptionContext> exceptionContext() {
        return value(EXCEPTION_CONTEXT);
    }

    public Optional<MlPrediction> mlResult() {
        return value(ML_RESULT);
    }

    public List<String> workflowErrors() {
        return this.<List<String>>value(WORKFLOW_ERRORS).orElse(List.of());
    }

    public Optional<String> resolution() {
        return value(RESOLUTION);
    }
}
