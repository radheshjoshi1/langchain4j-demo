package org.example.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.regex.Pattern;

/**
 * Masks secrets and PII-shaped substrings (API keys/tokens, emails, SSNs, card/account numbers)
 * out of whatever the model is about to return, in case a tool result or a hallucinated answer
 * surfaces more than the user should see back as prose. The same categories
 * {@link org.example.service.AccountService#flagSecurityRisk(String)} looks for (SECRET_KEY,
 * API_KEY, TOKEN) are covered here too, but enforced on every response instead of depending on the
 * model choosing to call that tool.
 * <p>
 * Redaction uses {@link #successWith(String)} rather than a failure - for a banking product,
 * responses are expected to legitimately reference account data, so the goal is masking, not
 * refusal.
 */
public class SecretsRedactionOutputGuardrail implements OutputGuardrail {

    private static final Pattern SECRET_TOKEN =
            Pattern.compile("\\b(SECRET_KEY|API_KEY|TOKEN)\\S*[:=]?\\s*[A-Za-z0-9_\\-]{4,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD_OR_ACCOUNT_NUMBER =
            Pattern.compile("\\b(?:\\d[ -]?){12,19}\\b");

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        if (text == null || text.isEmpty()) {
            return success();
        }

        String redacted = redact(text);
        return redacted.equals(text) ? success() : successWith(redacted);
    }

    private static String redact(String text) {
        text = SECRET_TOKEN.matcher(text).replaceAll("[REDACTED_SECRET]");
        text = EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]");
        text = SSN.matcher(text).replaceAll("[REDACTED_SSN]");
        text = CARD_OR_ACCOUNT_NUMBER.matcher(text).replaceAll("[REDACTED_NUMBER]");
        return text;
    }
}
