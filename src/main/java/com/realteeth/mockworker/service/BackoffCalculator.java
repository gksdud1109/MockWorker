package com.realteeth.mockworker.service;

import java.time.Duration;

final class BackoffCalculator {
    private BackoffCalculator() {}

    /** Exponential backoff with a cap. attempt is 1-based for the first retry. */
    static Duration next(Duration initial, Duration max, int attempt) {
        if (attempt < 1) attempt = 1;
        // cap the shift to avoid overflow; we never need more than ~20 doublings
        int shift = Math.min(attempt - 1, 20);
        long millis = initial.toMillis() * (1L << shift);
        long capped = Math.min(millis, max.toMillis());
        return Duration.ofMillis(capped);
    }
}
