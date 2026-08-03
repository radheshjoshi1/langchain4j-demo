package org.example.util;

import org.example.config.LangfuseConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpUtil {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Sends a POST / PATCH request with a JSON body.
     */
    public String sendPostRequest(String url, String jsonPayload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", LangfuseConfig.basicAuthHeader())
                .header("Content-Type", "application/json") // Required header
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload)) // Send payload in body
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP POST Request failed", e);
        }
    }

    /**
     * Sends a GET request (e.g. fetching datasets with query params).
     */
    public String sendGetRequest(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", LangfuseConfig.basicAuthHeader())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP GET Request failed", e);
        }
    }
}