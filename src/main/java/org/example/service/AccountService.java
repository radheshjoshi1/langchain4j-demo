package org.example.service;

import dev.langchain4j.agent.tool.Tool;

import java.util.HashMap;
import java.util.Map;

public class AccountService {

    private final Map<String, String> accountDatabase = new HashMap<>();

    public AccountService() {
        accountDatabase.put("CUS_001", "VIP");
        accountDatabase.put("CUS_002", "STANDARD");
    }

    @Tool("Retrieves the account tier for a given customer ID.")
    public String getAccountTier(String customerId) {
        System.out.println("\n[SYSTEM NOTICE]: LLM is executing 'getAccountTier' tool for ID: " + customerId);
        return accountDatabase.getOrDefault(customerId, "UNKNOWN");
    }

    @Tool("Checks for security risks in the provided text.")
    public String flagSecurityRisk(String text) {
        System.out.println("\n[SYSTEM NOTICE]: LLM is executing 'flagSecurityRisk' tool for text: " + text);
        if(text.contains("SECRET_KEY") || text.contains("API_KEY") || text.contains("TOKEN"))
            return "RISK_DETECTED";
        else
            return "NO_RISK";
    }
}
