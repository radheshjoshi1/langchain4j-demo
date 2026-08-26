package org.example.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Cheap first-layer defense against copy-pasted prompt-injection/jailbreak templates (OWASP
 * LLM01), applied to the raw user message before it is sent to the model. Pattern matching only -
 * it won't catch semantic injection phrased in a novel way, but it stops the common jailbreak
 * templates for near-zero cost.
 * <p>
 * A match produces a non-fatal {@link #failure(String)} rather than {@link #fatal(String)}, so a
 * false positive on legitimate phrasing surfaces as a normal error to the caller instead of being
 * indistinguishable from a framework crash.
 */
public class PromptInjectionInputGuardrail implements InputGuardrail {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(?:\\w+\\s+){0,3}?(previous|prior|above|earlier)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(?:\\w+\\s+){0,3}?(previous|prior|above|earlier)\\s+(instructions|prompts?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(?:\\w+\\s+){0,3}?instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(in\\s+)?(dan|developer|jailbreak|unrestricted)\\s*mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reveal\\s+(your\\s+)?(system\\s+prompt|instructions|hidden\\s+prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(act|pretend)\\s+as\\s+(if\\s+you\\s+(are|were)|an?\\s+unrestricted)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("print\\s+(your\\s+)?(system\\s+prompt|initial\\s+prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        if (text == null) {
            return success();
        }

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return failure("Message matched a known prompt-injection pattern and was blocked.");
            }
        }
        return success();
    }
}
