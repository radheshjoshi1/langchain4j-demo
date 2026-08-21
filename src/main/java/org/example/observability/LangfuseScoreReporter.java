package org.example.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.LangfuseConfig;
import org.example.models.EvaluationScore;
import org.example.util.HttpStatusException;
import org.example.util.HttpUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Posts {@link EvaluationScore}s to Langfuse's public scores API. Shared by every caller that
 * scores a trace (dataset runs, JUnit experiment runs, ...) so the posting/error-handling logic
 * lives in one place.
 */
public class LangfuseScoreReporter {

    private final HttpUtil httpUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangfuseScoreReporter(HttpUtil httpUtil) {
        this.httpUtil = httpUtil;
    }

    public void postScore(String traceId, EvaluationScore score) {
        if (traceId == null || traceId.isEmpty()) {
            System.out.println("[Langfuse Score Skipped]: No traceId for score " + score.name());
            return;
        }

        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/scores";

            Map<String, Object> body = new HashMap<>();
            body.put("id", score.id());
            body.put("traceId", traceId);
            body.put("name", score.name());
            body.put("value", score.value());
            body.put("dataType", score.dataType());
            body.put("comment", score.comment());

            String jsonPayload = objectMapper.writeValueAsString(body);
            httpUtil.sendPostRequest(url, jsonPayload);
            System.out.println("[Langfuse Score Posted]: " + score.name() + " = " + score.value());
        } catch (Exception e) {
            // Everything we know about the failure - so if this can't be root-caused from
            // Langfuse's side, this line alone has enough to go on: which score, which trace,
            // and (since HttpUtil surfaces HTTP status + response body on non-2xx) why.
            System.err.println("[Langfuse API]: Failed to post score '" + score.name()
                    + "' (id=" + score.id() + ", traceId=" + traceId + ", value=" + score.value()
                    + ", dataType=" + score.dataType() + "): " + e.getMessage());

            // A 429 means the marker POST would just spend more of the same exhausted budget
            // and fail too. Skip it here rather than pile onto the backlog; the rate limiter in
            // HttpUtil is what actually prevents this going forward.
            if (e instanceof HttpStatusException httpStatusException && httpStatusException.statusCode() == 429) {
                System.err.println("[Langfuse API]: Skipping failure marker for '" + score.name()
                        + "' - failure was a rate limit (429), not worth spending more quota on.");
                return;
            }
            postScoreFailureMarker(traceId, score, e);
        }
    }

    /**
     * Best-effort: when a real score fails to post, push a companion marker score so the failure
     * itself is visible on the trace in Langfuse, not just in the caller's console output.
     */
    private void postScoreFailureMarker(String traceId, EvaluationScore failedScore, Exception cause) {
        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/scores";
            String markerId = UUID.nameUUIDFromBytes(
                    (failedScore.id() + ":post_failed").getBytes(StandardCharsets.UTF_8)).toString();
            String comment = "Failed to post '" + failedScore.name() + "': " + cause.getMessage();

            Map<String, Object> body = new HashMap<>();
            body.put("id", markerId);
            body.put("traceId", traceId);
            body.put("name", failedScore.name() + "_post_failed");
            body.put("value", 1);
            body.put("dataType", "BOOLEAN");
            body.put("comment", comment.length() > 500 ? comment.substring(0, 500) : comment);

            String jsonPayload = objectMapper.writeValueAsString(body);
            httpUtil.sendPostRequest(url, jsonPayload);
            System.err.println("[Langfuse API]: Posted failure marker for score '" + failedScore.name() + "'.");
        } catch (Exception e) {
            System.err.println("[Langfuse API]: Also failed to post failure marker for score '"
                    + failedScore.name() + "': " + e.getMessage());
        }
    }
}
