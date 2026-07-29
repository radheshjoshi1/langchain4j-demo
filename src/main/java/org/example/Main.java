package org.example;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.example.config.OpenTelemetryConfig;
import org.example.service.AgentFactory;
import org.example.service.StreamingSupportAgent;

import java.util.Scanner;
import java.util.Set;
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

        StreamingSupportAgent streamingSupportAgent = AgentFactory.createAgent();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            openTelemetry.close();
            System.out.println("[System]: Shutdown complete.");
        }));

        runConsoleChat(streamingSupportAgent);
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
