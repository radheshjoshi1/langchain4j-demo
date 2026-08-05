package org.example.util;

/**
 * Thrown when an HTTP call completes but returns a non-2xx status, so callers can branch on the
 * actual status code (e.g. back off on 429) instead of string-matching the message.
 */
public class HttpStatusException extends RuntimeException {

    private final int statusCode;

    public HttpStatusException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
