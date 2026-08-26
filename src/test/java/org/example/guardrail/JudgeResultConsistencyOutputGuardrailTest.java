package org.example.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeResultConsistencyOutputGuardrailTest {

    private final JudgeResultConsistencyOutputGuardrail guardrail = new JudgeResultConsistencyOutputGuardrail();

    @Test
    void acceptsWellFormedJudgeResult() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("{\"score\": 0.8, \"rationale\": \"Answer matched the expected output.\"}"));

        assertTrue(result.isSuccess());
        assertFalse(result.hasRewrittenResult());
    }

    @Test
    void repromptsOnInvalidJson() {
        OutputGuardrailResult result = guardrail.validate(AiMessage.from("not json at all"));

        assertFalse(result.isSuccess());
        assertTrue(result.isFatal());
    }

    @Test
    void repromptsWhenScoreOutOfRange() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("{\"score\": 1.5, \"rationale\": \"Great answer.\"}"));

        assertFalse(result.isSuccess());
        assertTrue(result.isFatal());
    }

    @Test
    void repromptsWhenRationaleBlank() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("{\"score\": 0.5, \"rationale\": \"\"}"));

        assertFalse(result.isSuccess());
        assertTrue(result.isFatal());
    }

    @Test
    void repromptsWhenScoreMissing() {
        OutputGuardrailResult result = guardrail.validate(
                AiMessage.from("{\"rationale\": \"Missing score entirely.\"}"));

        assertFalse(result.isSuccess());
        assertTrue(result.isFatal());
    }
}
