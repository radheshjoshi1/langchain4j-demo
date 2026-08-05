package org.example.eval.judge;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

@Description("Structured LLM-as-judge evaluation of a banking assistant's answer against the expected output.")
public class JudgeResult {

    @JsonProperty(required = true)
    @Description("Answer quality score from 0.0 (completely wrong) to 1.0 (fully correct), judged against expectedOutput.")
    private double score;

    @JsonProperty(required = true)
    @Description("Short rationale for the score. Do not wrap in Markdown fences.")
    private String rationale;

    public JudgeResult() {
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }
}
