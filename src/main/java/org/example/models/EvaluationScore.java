package org.example.models;

public record EvaluationScore(
        String id,          // deterministic UUID (Type-3, hashed from runName:itemId:metricName)
        String name,        // e.g., "execution_success", "latency_within_budget", "intent_match"
        Object value,       // Integer 1/0 for BOOLEAN, double/float for NUMERIC
        String dataType,    // "BOOLEAN" or "NUMERIC"
        String comment      // Rationale/details for the score
) {}
