package org.example.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

/**
 * Validates semantic consistency of {@code org.example.eval.judge.JudgeResult} that LangChain4j's
 * JSON-schema deserialization can't catch on its own: {@code score} must fall within the
 * documented 0.0-1.0 range, and {@code rationale} must be non-blank. Reprompts the model with a
 * corrective instruction rather than failing outright.
 * <p>
 * Output guardrails see the raw {@link AiMessage} text, not the already-deserialized POJO, so
 * this parses the JSON itself the same way the (deprecated)
 * {@link dev.langchain4j.guardrail.JsonExtractorOutputGuardrail} did, rather than depending on the
 * POJO's own fields.
 */
public class JudgeResultConsistencyOutputGuardrail implements OutputGuardrail {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(responseFromLLM.text());
        } catch (Exception e) {
            return reprompt("Judge response was not valid JSON.",
                    "Return only the structured judge response fields as valid JSON.");
        }

        if (!node.hasNonNull("score") || !node.get("score").isNumber()) {
            return reprompt("Judge response is missing a numeric score.",
                    "Return a JSON object with a numeric \"score\" field between 0.0 and 1.0.");
        }

        double score = node.get("score").asDouble();
        if (score < 0.0 || score > 1.0) {
            return reprompt("Judge score " + score + " is outside the valid 0.0-1.0 range.",
                    "The \"score\" field must be between 0.0 and 1.0. Re-score using that range.");
        }

        if (!node.hasNonNull("rationale") || node.get("rationale").asText().isBlank()) {
            return reprompt("Judge response is missing a rationale.",
                    "Return a JSON object that also includes a non-empty \"rationale\" field explaining the score.");
        }

        return success();
    }
}
