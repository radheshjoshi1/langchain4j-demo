package org.example.ml;

import org.example.exceptions.ExceptionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlaceholderLocalMlModelClientTest {

    @Test
    void alwaysRoutesToManualReview() {
        var client = new PlaceholderLocalMlModelClient();
        var context = new ExceptionContext("TXN_001", "CUS_001", "FAILED", "Invalid HMAC Signature.", "VIP");

        var prediction = client.predict(context);

        assertFalse(prediction.bypassEligible());
        assertEquals("MANUAL_REVIEW", prediction.label());
    }
}
