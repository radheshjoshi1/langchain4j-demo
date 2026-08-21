package org.example.util;

/**
 * Thrown when a call to the embedded (in-process) local model - model load, download, or
 * inference - fails, so callers can catch this specific type and decide how to resolve it
 * (fallback response, retry, disable the tool) instead of an opaque RuntimeException from
 * Jlama's internals reaching them.
 */
public class LocalModelUnavailableException extends RuntimeException {

    public LocalModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
