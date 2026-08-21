package org.example.exceptions;

import org.bsc.langgraph4j.GraphStateException;
import org.example.ml.LocalMlModelClient;
import org.example.ml.LocalMlModelClientFactory;
import org.example.ml.MlModelConfig;
import org.example.service.AccountService;
import org.example.service.PaymentService;

/**
 * Wires up the exception-triage graph with the ML client selected by {@link MlModelConfig} - the
 * real ONNX-backed one when {@code EXCEPTION_ML_ENABLED=true} and a model file is reachable, the
 * placeholder otherwise.
 */
public final class ExceptionAgentFactory {

    private ExceptionAgentFactory() {
    }

    public static ExceptionAgentProcessor createProcessor() throws GraphStateException {
        MlModelConfig config = MlModelConfig.fromEnvironment();
        LocalMlModelClient mlModelClient = LocalMlModelClientFactory.create(config);
        var graph = ExceptionAgentGraphFactory.build(new PaymentService(), new AccountService(), mlModelClient);
        return new ExceptionAgentProcessor(graph);
    }
}
