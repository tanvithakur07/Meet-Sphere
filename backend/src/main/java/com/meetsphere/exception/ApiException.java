package com.meetsphere.exception;

/**
 * ApiException — a "safe" business error whose message may be shown to the client.
 * Any other exception is treated as unexpected: it is logged and a generic message is returned.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String message) {
        this(message, 400);
    }

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
