package com.realteeth.mockworker.client;

/**
 * Thrown by {@link MockWorkerClient} when the worker call fails.
 *
 * {@code transient_} is the important bit: callers use it to decide retry vs. fail.
 *   - true: network I/O error, 5xx, 429, timeout. Safe to retry.
 *   - false: 4xx (except 429), malformed response. Retrying will not help.
 */
public class MockWorkerException extends RuntimeException {
    private final boolean transient_;

    public MockWorkerException(String message, boolean isTransient) {
        super(message);
        this.transient_ = isTransient;
    }

    public MockWorkerException(String message, Throwable cause, boolean isTransient) {
        super(message, cause);
        this.transient_ = isTransient;
    }

    public boolean isTransient() {
        return transient_;
    }
}
