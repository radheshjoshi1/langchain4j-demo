package org.example.config;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

/**
 * Handles initialization of OpenTelemetry SDK.
 */
public final class OpenTelemetryConfig {

    private OpenTelemetryConfig() {
    }

    /**
     * Initializes OpenTelemetry with Langfuse span exporter.
     *
     * @return the initialized OpenTelemetrySdk
     */
    public static OpenTelemetrySdk initOpenTelemetry() {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(LangfuseConfig.otlpEndpoint())
                .addHeader("Authorization", LangfuseConfig.basicAuthHeader())
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
