package org.example.ml;

import org.example.exceptions.ExceptionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link OnnxLocalMlModelClient} against a real (hand-crafted, not trained) fixture
 * model checked into {@code src/test/resources/models/exception-bypass-classifier.onnx} - proves
 * feature-vector construction and score extraction against actual ONNX Runtime inference, not
 * just the placeholder fallback path.
 */
class OnnxLocalMlModelClientTest {

    private static final MlModelConfig CONFIG = new MlModelConfig(
            true,
            "classpath:models/exception-bypass-classifier.onnx",
            List.of("statusCode", "accountTierCode", "reasonLength"),
            0.5,
            "test-fixture-v1");

    @Test
    void successfulFailedPaymentScoresBelowThresholdAndIsNotBypassEligible() throws Exception {
        try (var client = new OnnxLocalMlModelClient(CONFIG)) {
            var context = new ExceptionContext("TXN_002", "CUS_002", "FAILED", "Invalid HMAC Signature.", "STANDARD");

            var prediction = client.predict(context);

            assertFalse(prediction.bypassEligible());
            assertEquals("MANUAL_REVIEW", prediction.label());
            assertTrue(prediction.score() < 0.5, "expected score below threshold, was " + prediction.score());
        }
    }

    @Test
    void successfulPaymentScoresAboveThresholdAndIsBypassEligible() throws Exception {
        try (var client = new OnnxLocalMlModelClient(CONFIG)) {
            var context = new ExceptionContext(
                    "TXN_001", "CUS_001", "SUCCESS", "Dispatched to settlement network.", "VIP");

            var prediction = client.predict(context);

            assertTrue(prediction.bypassEligible());
            assertEquals("AUTO_RESOLVE", prediction.label());
            assertTrue(prediction.score() >= 0.5, "expected score at/above threshold, was " + prediction.score());
        }
    }

    @Test
    void modelVersionReflectsConfig() throws Exception {
        try (var client = new OnnxLocalMlModelClient(CONFIG)) {
            assertEquals("test-fixture-v1", client.modelVersion());
        }
    }
}
