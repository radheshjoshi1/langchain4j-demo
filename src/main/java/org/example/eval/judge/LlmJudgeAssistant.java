package org.example.eval.judge;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface LlmJudgeAssistant {

    @SystemMessage("""
            You are an evaluation judge for a banking support assistant.
            Score whether the actual response correctly and helpfully answers the user's
            question, using the expected output as a reference where one is provided.
            Be strict about factually wrong answers, ignored questions, or answers that
            contradict the expected output.
            Return only the structured judge response fields.
            """)
    @UserMessage("{{prompt}}")
    JudgeResult judge(@V("prompt") String prompt);
}
