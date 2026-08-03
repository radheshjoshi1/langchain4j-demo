package org.example.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetItem {
    private String id;
    private String datasetId;
    private String sourceTraceId;
    private String sourceObservationId;
    private String status;
    private String createdAt;
    private String updatedAt;
    private List<ChatMessage> input;
    private String actualOutput;
    private Object expectedOutput; // Object handles String, null, or JSON Map
    private Object metadata;
    private String datasetName;
    private List<Object> mediaReferences;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getSourceTraceId() { return sourceTraceId; }
    public void setSourceTraceId(String sourceTraceId) { this.sourceTraceId = sourceTraceId; }

    public String getSourceObservationId() { return sourceObservationId; }
    public void setSourceObservationId(String sourceObservationId) { this.sourceObservationId = sourceObservationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public List<ChatMessage> getInput() { return input; }
    public void setInput(List<ChatMessage> input) { this.input = input; }

    public Object getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(Object expectedOutput) { this.expectedOutput = expectedOutput; }

    public Object getMetadata() { return metadata; }
    public void setMetadata(Object metadata) { this.metadata = metadata; }

    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }

    public List<Object> getMediaReferences() { return mediaReferences; }
    public void setMediaReferences(List<Object> mediaReferences) { this.mediaReferences = mediaReferences; }

    public String getActualOutput() {
        return actualOutput;
    }

    public void setActualOutput(String actualOutput) {
        this.actualOutput = actualOutput;
    }
}