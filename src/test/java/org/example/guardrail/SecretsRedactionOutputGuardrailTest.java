package org.example.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsRedactionOutputGuardrailTest {

    private final SecretsRedactionOutputGuardrail guardrail = new SecretsRedactionOutputGuardrail();

    @Test
    void redactsApiKeysAndTokens() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("Sure, here is your API_KEY: sk-abc123XYZ for the integration."));

        assertTrue(result.isSuccess());
        assertTrue(result.hasRewrittenResult());
        assertFalse(result.successfulText().contains("sk-abc123XYZ"));
        assertTrue(result.successfulText().contains("[REDACTED_SECRET]"));
    }

    @Test
    void redactsEmailAddresses() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("You can reach support at jane.doe@example.com for help."));

        assertTrue(result.hasRewrittenResult());
        assertFalse(result.successfulText().contains("jane.doe@example.com"));
        assertTrue(result.successfulText().contains("[REDACTED_EMAIL]"));
    }

    @Test
    void redactsCardOrAccountNumbers() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("Your linked card number is 4111111111111111."));

        assertTrue(result.hasRewrittenResult());
        assertFalse(result.successfulText().contains("4111111111111111"));
        assertTrue(result.successfulText().contains("[REDACTED_NUMBER]"));
    }

    @Test
    void leavesOrdinaryResponsesUnchanged() {
        String text = "Your account tier is VIP and transaction TXN_001 succeeded.";
        OutputGuardrailResult result = guardrail.validate(AiMessage.from(text));

        assertTrue(result.isSuccess());
        assertFalse(result.hasRewrittenResult());
    }
}
