package org.example.exceptions;

import org.bsc.langgraph4j.CompiledGraph;

import java.util.Map;

/**
 * Runs one transaction through the exception graph and returns the final resolution text.
 */
public class ExceptionAgentProcessor {

    private final CompiledGraph<ExceptionAgentState> graph;

    public ExceptionAgentProcessor(CompiledGraph<ExceptionAgentState> graph) {
        this.graph = graph;
    }

    public String process(String transactionId, String customerId) {
        Map<String, Object> inputs = Map.of(
                ExceptionAgentState.TRANSACTION_ID, transactionId,
                ExceptionAgentState.CUSTOMER_ID, customerId);

        return graph.invoke(inputs)
                .flatMap(ExceptionAgentState::resolution)
                .orElse("No resolution produced for transaction " + transactionId);
    }
}
