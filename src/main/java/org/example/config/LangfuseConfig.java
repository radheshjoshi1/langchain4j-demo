package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/**
 * Centralised configuration for Langfuse credentials and URLs.
 */
public final class LangfuseConfig {

    private static final String PROPERTIES_FILE = "langfuse.properties";

    private static final String publicKey;
    private static final String secretKey;
    private static final String baseUrl;
    private static final String otlpEndpoint;
    private static final String basicAuthHeader;
    private static final String traceName;

    static {
        Properties props = new Properties();
        try (InputStream is = LangfuseConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                props.load(is);
            } else {
                System.err.println("[LangfuseConfig]: langfuse.properties not found on classpath. Using env vars / defaults.");
            }
        } catch (IOException e) {
            System.err.println("[LangfuseConfig]: Error loading langfuse.properties: " + e.getMessage());
        }

        publicKey = System.getenv().getOrDefault("LANGFUSE_PUBLIC_KEY",
                props.getProperty("langfuse.public-key", ""));
        secretKey = System.getenv().getOrDefault("LANGFUSE_SECRET_KEY",
                props.getProperty("langfuse.secret-key", ""));
        baseUrl = System.getenv().getOrDefault("LANGFUSE_BASE_URL",
                props.getProperty("langfuse.base-url", "https://us.cloud.langfuse.com"));
        traceName = System.getenv().getOrDefault("LANGFUSE_TRACE_NAME",
                props.getProperty("langfuse.trace-name", "chatbot"));

        otlpEndpoint = baseUrl + "/api/public/otel/v1/traces";
        basicAuthHeader = "Basic " + Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
    }

    private LangfuseConfig() {
    }

    /**
     * Retrieves the public key.
     *
     * @return the public key
     */
    public static String publicKey() {
        return publicKey;
    }

    /**
     * Retrieves the secret key.
     *
     * @return the secret key
     */
    public static String secretKey() {
        return secretKey;
    }

    /**
     * Retrieves the base URL.
     *
     * @return the base URL
     */
    public static String baseUrl() {
        return baseUrl;
    }

    /**
     * Retrieves the OTLP traces endpoint.
     *
     * @return the OTLP endpoint
     */
    public static String otlpEndpoint() {
        return otlpEndpoint;
    }

    /**
     * Retrieves the pre-built Basic authorization header value.
     *
     * @return the basic authorization header
     */
    public static String basicAuthHeader() {
        return basicAuthHeader;
    }

    /**
     * Retrieves the configured trace name.
     *
     * @return the trace name
     */
    public static String traceName() {
        return traceName;
    }
}
