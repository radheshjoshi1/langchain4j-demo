package org.example.eval;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.example.config.OpenTelemetryConfig;
import org.example.eval.RunResult;
import org.example.models.DatasetResponse;
import org.example.service.AgentFactory;
import org.example.service.StreamingSupportAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style experiment test that drives {@link DatasetItemRunner} against the real
 * {@code banking-assistant} dataset in Langfuse.
 *
 * <p>Each {@code @Test} method represents one experiment run. Results are uploaded to Langfuse
 * (dataset-run-item links + OTel span attribute scores) during the run, so you can inspect
 * per-item traces in the Langfuse dashboard after the test completes.
 *
 * <h2>Run</h2>
 * <pre>
 *   mvn test -Dtest=EvalExperimentTest
 * </pre>
 *
 * <h2>Pass criteria</h2>
 * <ul>
 *   <li>At least {@value #MIN_EXECUTION_SUCCESS_RATE}% of dataset items must produce a
 *       non-empty agent response ({@code execution_success = 1}).</li>
 * </ul>
 */
class EvalExperimentTest {

    /** Minimum fraction of items that must pass {@code execution_success} for the test to pass. */
    private static final double MIN_EXECUTION_SUCCESS_RATE = 0.80;

    private static final String DATASET_NAME = "banking-assistant";

    private static OpenTelemetrySdk openTelemetry;
    private static DatasetItemRunner runner;

    @BeforeAll
    static void setUp() {
        // Bootstrap OTel so spans are exported to Langfuse exactly as in production.
        openTelemetry = OpenTelemetryConfig.initOpenTelemetry();

        // Build the streaming agent (no session ID — DatasetItemRunner manages session IDs
        // per-run on the root span, matching the same design as Main.java's dataset-run path).
        StreamingSupportAgent agent = AgentFactory.createAgent();
        runner = new DatasetItemRunner(agent);
    }

    @AfterAll
    static void tearDown() {
        // Flush all pending OTel spans before the JVM exits so Langfuse receives every trace.
        if (openTelemetry != null) {
            openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            openTelemetry.close();
        }
    }

    /**
     * Runs the full {@code banking-assistant} dataset from Langfuse through the agent and asserts
     * that at least 80% of items pass {@code execution_success}.
     *
     * <p>All scores are uploaded as OTel span attributes (visible as trace scores in Langfuse).
     * Each item's dataset-run-item link is posted via REST so the run appears in the Langfuse
     * "Dataset Runs" view.
     */
    @Test
    void bankingAssistantDataset_executionSuccessRate_atLeast80Percent() {
        // -- Arrange: fetch dataset items from Langfuse --
        DatasetResponse dataset = runner.fetchDatasetItems(DATASET_NAME);
        assertNotNull(dataset, "Dataset response must not be null");
        assertNotNull(dataset.getData(), "Dataset items list must not be null");
        assertFalse(dataset.getData().isEmpty(),
                "Dataset '" + DATASET_NAME + "' must contain at least one item");

        int totalItems = dataset.getData().size();
        System.out.printf("%n[EvalExperimentTest]: Running %d items from dataset '%s'...%n",
                totalItems, DATASET_NAME);

        // -- Act: run the experiment --
        String runName = "junit-run-" + System.currentTimeMillis();
        List<RunResult> results = runner.runDataset(dataset, runName);

        // -- Assert: overall result count --
        assertEquals(totalItems, results.size(),
                "Expected one RunResult per dataset item");

        // -- Assert: >= 80% execution_success --
        long passed = results.stream()
                .filter(r -> r.passed("execution_success"))
                .count();

        double actualRate = (double) passed / results.size();
        System.out.printf("[EvalExperimentTest]: execution_success pass rate = %.0f%% (%d/%d)%n",
                actualRate * 100, passed, results.size());

        assertTrue(actualRate >= MIN_EXECUTION_SUCCESS_RATE,
                String.format("execution_success pass rate %.0f%% is below the required %.0f%% threshold (%d/%d items passed).",
                        actualRate * 100, MIN_EXECUTION_SUCCESS_RATE * 100, passed, results.size()));
    }
}
