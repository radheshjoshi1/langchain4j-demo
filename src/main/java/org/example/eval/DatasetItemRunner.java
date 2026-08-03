package org.example.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.LangfuseConfig;
import org.example.models.ChatMessage;
import org.example.models.DatasetItem;
import org.example.models.DatasetResponse;
import org.example.service.StreamingSupportAgent;
import org.example.util.HttpUtil;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DatasetItemRunner {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final StreamingSupportAgent supportAgent;

    public DatasetItemRunner(StreamingSupportAgent supportAgent) {
        this.supportAgent = supportAgent;
    }

    public void runDataset(DatasetResponse datasetResponse) {
        List<DatasetItem> items = datasetResponse.getData();
        for (DatasetItem item : items) {
            run(item);
            break;
        }
    }

    public void run(DatasetItem item) {

        String userPrompt = extractLatestUserPrompt(item.getInput());
        if (userPrompt == null) {
            System.out.println("[Eval]: Skipped item (No user prompt found).");
            return;
        }

        System.out.println("[Prompt]: " + userPrompt);
        System.out.print("[Agent Response]: ");

        CompletableFuture<String> futureResponse = new CompletableFuture<>();
        StringBuilder actualOutputBuilder = new StringBuilder();

        supportAgent.chat(userPrompt)
                .onPartialResponse(token -> {
                    actualOutputBuilder.append(token);
                    System.out.print(token);
                    System.out.flush();
                })
                .onCompleteResponse(response -> {
                    System.out.println(); // New line after streaming
                    futureResponse.complete(actualOutputBuilder.toString());
                })
                .onError(error -> {
                    System.out.println();
                    System.err.println("[Eval]: Error during agent response: " + error.getMessage());
                    futureResponse.completeExceptionally(error);
                })
                .start();

        try {
            System.out.println("[Expected Output]: " + item.getExpectedOutput());
            postDatasetRunOutput("run-1", item.getId(), item.getSourceTraceId());
        } catch (Exception e) {
            System.err.println("[Eval]: Failed turn execution for item ID: " + item.getId());
        }
    }

    private String extractLatestUserPrompt(List<ChatMessage> inputMessages) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return null;
        }

        // Iterate backwards to find the last user turn
        for (int i = inputMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = inputMessages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    public DatasetResponse fetchDatasetItems(String dataSetName){
        String url = LangfuseConfig.baseUrl() + "/api/public/dataset-items?datasetName=" + dataSetName;
        HttpUtil httpUtil = new HttpUtil();
        String response = httpUtil.sendGetRequest(url);

        try {
            return objectMapper.readValue(response, DatasetResponse.class);
        } catch (JsonProcessingException e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void postDatasetRunOutput(String runName, String datasetItemId, String traceId) {
        try {
            String url = LangfuseConfig.baseUrl() + "/api/public/dataset-run-items";

            Map<String, Object> body = new HashMap<>();
            body.put("runName", runName);
            body.put("datasetItemId", datasetItemId);
            body.put("traceId", traceId);

            String jsonPayload = objectMapper.writeValueAsString(body);
            HttpUtil httpUtil = new HttpUtil();
            String response = httpUtil.sendPostRequest(url, jsonPayload);
            System.out.println(response);
        } catch (Exception e) {
            System.err.println("[Langfuse API]: Exception logging dataset run output: " + e.getMessage());
        }
    }
}
