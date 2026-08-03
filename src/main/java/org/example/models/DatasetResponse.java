package org.example.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetResponse {
    private List<DatasetItem> data;
    private Meta meta;

    public List<DatasetItem> getData() { return data; }
    public void setData(List<DatasetItem> data) { this.data = data; }

    public Meta getMeta() { return meta; }
    public void setMeta(Meta meta) { this.meta = meta; }
}