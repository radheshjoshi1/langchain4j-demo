package org.example.exceptions;

import java.io.Serializable;
import java.util.Map;

/**
 * Everything the ML classifier and the terminal nodes need about one payment exception - a
 * failed or pending transaction pulled via {@code PaymentService}/{@code AccountService}.
 *
 * <p>Implements {@link Serializable}: langgraph4j's default state serializer clones each node's
 * state via Java serialization between steps, so every value placed into {@link
 * ExceptionAgentState} needs to support that.
 */
public record ExceptionContext(
        String transactionId,
        String customerId,
        String paymentStatus,
        String reasonDetail,
        String accountTier) implements Serializable {

    /**
     * Numeric feature map the local ML client reads from, keyed by the names configured in
     * {@code EXCEPTION_ML_FEATURE_ORDER} - keeps the feature set data-driven instead of
     * hardcoded in {@link org.example.ml.OnnxLocalMlModelClient}.
     */
    public Map<String, Object> features() {
        return Map.of(
                "statusCode", statusCode(),
                "accountTierCode", accountTierCode(),
                "reasonLength", reasonDetail == null ? 0 : reasonDetail.length());
    }

    private int statusCode() {
        return switch (paymentStatus == null ? "" : paymentStatus.toUpperCase()) {
            case "SUCCESS" -> 0;
            case "PENDING" -> 1;
            case "FAILED" -> 2;
            default -> -1;
        };
    }

    private int accountTierCode() {
        return switch (accountTier == null ? "" : accountTier.toUpperCase()) {
            case "STANDARD" -> 0;
            case "VIP" -> 1;
            default -> -1;
        };
    }
}
