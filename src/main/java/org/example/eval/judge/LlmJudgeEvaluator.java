package org.example.eval.judge;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.example.eval.DeterministicEvaluator;
import org.example.models.EvaluationScore;

/**
 * Bedrock/OpenAI-style LLM-as-judge, scored 0.0-1.0 and posted to Langfuse as "llm_judge".
 * The chat model and AiServices proxy are built once and reused across items.
 */
public class LlmJudgeEvaluator {

    public static final String SCORE_NAME = "llm_judge";

    private static final String JUDGE_MODEL_NAME = "gpt-4o-mini";

    private final LlmJudgeAssistant assistant;

    public LlmJudgeEvaluator() {
        ChatModel judgeModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName(JUDGE_MODEL_NAME)
                .temperature(0.0)
                .maxTokens(300)
                .build();
        this.assistant = AiServices.builder(LlmJudgeAssistant.class)
                .chatModel(judgeModel)
                .build();
    }

    public EvaluationScore evaluate(String runName, String itemId, String question, String expectedOutput,
                                     String actualOutput) {
        String id = DeterministicEvaluator.scoreId(runName, itemId, SCORE_NAME);
        try {
            JudgeResult result = assistant.judge(buildPrompt(question, expectedOutput, actualOutput));
            double score = clamp(result.getScore());
            String rationale = result.getRationale() == null || result.getRationale().isBlank()
                    ? "No rationale provided by judge."
                    : result.getRationale().trim();
            return new EvaluationScore(id, SCORE_NAME, score, "NUMERIC", rationale);
        } catch (Exception e) {
            return new EvaluationScore(id, SCORE_NAME, 0.0, "NUMERIC",
                    "Judge error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String buildPrompt(String question, String expectedOutput, String actualOutput) {
        return """
                question: %s
                expectedOutput: %s
                actualOutput: %s

                Score actualOutput from 0.0 to 1.0 against expectedOutput. If expectedOutput is
                "(none provided)", judge actualOutput purely on whether it correctly and
                helpfully answers question.
                """.formatted(
                nullToEmpty(question),
                expectedOutput == null || expectedOutput.isBlank() ? "(none provided)" : expectedOutput,
                nullToEmpty(actualOutput));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
