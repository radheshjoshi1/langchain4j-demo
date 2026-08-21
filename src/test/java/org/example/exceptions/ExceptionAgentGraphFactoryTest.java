package org.example.exceptions;

import org.example.ml.PlaceholderLocalMlModelClient;
import org.example.service.AccountService;
import org.example.service.PaymentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionAgentGraphFactoryTest {

    @Test
    void placeholderClientRoutesEveryTransactionToManualReviewEndToEnd() throws Exception {
        var graph = ExceptionAgentGraphFactory.build(
                new PaymentService(), new AccountService(), new PlaceholderLocalMlModelClient());
        var processor = new ExceptionAgentProcessor(graph);

        String resolution = processor.process("TXN_002", "CUS_002");

        assertTrue(resolution.contains("MANUAL REVIEW"));
        assertTrue(resolution.contains("TXN_002"));
    }
}
