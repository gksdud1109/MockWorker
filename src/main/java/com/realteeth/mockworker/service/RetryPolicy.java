package com.realteeth.mockworker.service;

import com.realteeth.mockworker.client.MockWorkerException;
import com.realteeth.mockworker.domain.ImageJob;
import java.time.Duration;
import java.time.Instant;

/**
 * Encapsulates "should we retry or fail?" decision and the backoff calculation.
 *
 * This removes the symmetric duplication between JobSubmitter.handleSubmitFailure and
 * JobPoller.handlePollFailure. Both had the same transient/permanent/max-attempts branching;
 * having it in two places means a change to retry strategy must touch two files simultaneously.
 *
 * @param initial     starting backoff duration
 * @param ceiling     maximum backoff duration
 * @param maxAttempts total attempts before giving up (null = unlimited)
 * @param phase       label used in the failure reason string ("submit" or "poll")
 */
public record RetryPolicy(Duration initial, Duration ceiling, Integer maxAttempts, String phase) {

    /**
     * Apply this policy to a job after a worker exception.
     * Mutates the job in-place (no save). Caller is responsible for persisting.
     *
     * @return true if the job was marked FAILED (caller may want to log differently)
     */
    public boolean apply(ImageJob job, MockWorkerException e, Instant now) {
        if (!e.isTransient()) {
            job.markFailed(phase + " permanent failure: " + e.getMessage(), now);
            return true;
        }
        if (maxAttempts != null && job.getAttemptCount() + 1 >= maxAttempts) {
            job.markFailed(phase + " max attempts exceeded: " + e.getMessage(), now);
            return true;
        }
        Duration backoff = BackoffCalculator.next(initial, ceiling, job.getAttemptCount());
        job.recordTransientFailure(now.plus(backoff), e.getMessage(), now);
        return false;
    }
}
