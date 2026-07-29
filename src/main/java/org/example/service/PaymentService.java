package org.example.service;

import dev.langchain4j.agent.tool.Tool;

import java.util.HashMap;
import java.util.Map;

public class PaymentService {

    private final Map<String, String> transactionDatabase = new HashMap<>();

    public PaymentService() {
        transactionDatabase.put("TXN_001", "SUCCESS - Dispatched to settlement network.");
        transactionDatabase.put("TXN_002", "FAILED - Invalid HMAC Signature.");
        transactionDatabase.put("TXN_003", "PENDING - Awaiting gateway confirmation.");
    }

    @Tool("Retrieves the payment status for a given transaction ID.")
    public String checkPaymentStatus(String transactionId) {
        System.out.println("\n[SYSTEM NOTICE]: LLM is executing 'checkPaymentStatus' tool for ID: " + transactionId);
        return transactionDatabase.getOrDefault(transactionId, "TRANSACTION_NOT_FOUND");
    }
}
