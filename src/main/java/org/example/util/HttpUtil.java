package org.example.util;

import org.example.config.LangfuseConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpUtil {

    // Langfuse Cloud's public API enforces 30 requests/60s on this key; stay under it with
    // margin so a dataset run's dataset-run-item + score POSTs don't spend most of the run 429'd.
    private static final RateLimiter LANGFUSE_RATE_LIMITER = new RateLimiter(25, Duration.ofSeconds(60));

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

        LANGFUSE_RATE_LIMITER.acquire();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new HttpStatusException(response.statusCode(), "HTTP POST to " + url + " failed with status "
                        + response.statusCode() + ": " + response.body());
            }
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

        LANGFUSE_RATE_LIMITER.acquire();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new HttpStatusException(response.statusCode(), "HTTP GET to " + url + " failed with status "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP GET Request failed", e);
        }
    }
}