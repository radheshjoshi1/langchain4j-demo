package org.example;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.example.config.OpenTelemetryConfig;
import org.example.eval.DatasetItemRunner;
import org.example.models.DatasetResponse;
import org.example.service.AgentFactory;
import org.example.service.StreamingSupportAgent;

import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main entrypoint class to boot the application.
 */
public class Main {

    private static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit");

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        OpenTelemetrySdk openTelemetry = OpenTelemetryConfig.initOpenTelemetry();

        System.out.println("=== Agent Online (Langfuse tracing enabled) ===");

        boolean consoleMode = args.length > 0 && "console".equalsIgnoreCase(args[0]);
        // POC model backends selectable in console mode:
        //   console          -> hosted OpenAI-compatible endpoint (default)
        //   console local    -> model served by Ollama on localhost (AgentFactory.createLocalAgent())
        //   console embedded -> model loaded and run in this JVM via Jlama, no separate process
        //                       (AgentFactory.createEmbeddedAgent())
        String backend = args.length > 1 ? args[1].toLowerCase() : "";

        // Only console mode needs this passed into the listener: a console turn has no root
        // span of its own to carry a session id, so LangfuseOtelListener has to stamp one on
        // the "chat ..." generation span directly. The dataset run gets its session grouping
        // from DatasetItemRunner's own per-run session id on each item's root span instead -
        // passing this one there too would stamp two conflicting session ids onto the same
        // trace (child generation spans export before the root span, which only ends after
        // scoring completes, so the two values raced and Langfuse split one run's traces
        // across two sessions instead of grouping them under one).
        String sessionId = "session-" + UUID.randomUUID();
        StreamingSupportAgent streamingSupportAgent;
        if ("local".equals(backend)) {
            streamingSupportAgent = AgentFactory.createLocalAgent();
        } else if ("embedded".equals(backend)) {
            streamingSupportAgent = AgentFactory.createEmbeddedAgent();
        } else if (consoleMode) {
            streamingSupportAgent = AgentFactory.createAgent(sessionId);
        } else {
            streamingSupportAgent = AgentFactory.createAgent();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            openTelemetry.close();
            System.out.println("[System]: Shutdown complete.");
        }));

        if (consoleMode) {
            runConsoleChat(streamingSupportAgent);
        } else {
            DatasetItemRunner runner = new DatasetItemRunner(streamingSupportAgent);
            DatasetResponse dataset = runner.fetchDatasetItems("banking-assistant");
            String runName = "run-" + System.currentTimeMillis();
            runner.runDataset(dataset, runName);
        }
    }

    private static void runConsoleChat(StreamingSupportAgent agent) {
        System.out.println("[System]: Type your message and press Enter. Type 'exit' to quit.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\nYou: ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String message = scanner.nextLine().trim();

            if (message.isEmpty()) {
                continue;
            }
            if (EXIT_COMMANDS.contains(message.toLowerCase())) {
                break;
            }

            System.out.print("Agent: ");
            CompletableFuture<Void> turnDone = new CompletableFuture<>();

            agent.chat(message)
                    .onPartialResponse(token -> {
                        System.out.print(token);
                        System.out.flush();
                    })
                    .onCompleteResponse(response -> {
                        System.out.println();
                        turnDone.complete(null);
                    })
                    .onError(error -> {
                        System.out.println();
                        System.err.println("[System]: Error: " + (error.getMessage() != null ? error.getMessage() : error));
                        turnDone.complete(null);
                    })
                    .start();

            turnDone.join();
        }

        scanner.close();
        System.out.println("[System]: Goodbye!");
    }
}
