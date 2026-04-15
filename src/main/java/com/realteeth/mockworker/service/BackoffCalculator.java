package com.realteeth.mockworker.service;

import java.time.Duration;

final class BackoffCalculator {
    private BackoffCalculator() {}

    /**
     * Exponential backoff with a ceiling.
     *
     * @param initial   base duration for the first retry
     * @param max       upper bound (never exceeded)
     * @param doneCount number of attempts already completed (0 = no attempt yet → first retry)
     *
     * Mapping: doneCount=0 → initial, doneCount=1 → initial*2, doneCount=2 → initial*4, ...
     * This makes the caller's intent unambiguous:
     *   {@code BackoffCalculator.next(initial, max, fresh.getAttemptCount())}
     */
    static Duration next(Duration initial, Duration max, int doneCount) {
        if (doneCount < 0) doneCount = 0;
        // cap the shift to avoid long overflow; 20 doublings is more than enough
        int shift = Math.min(doneCount, 20);
        long millis = initial.toMillis() * (1L << shift);
        long capped = Math.min(millis, max.toMillis());
        return Duration.ofMillis(capped);
    }
}
