package com.realteeth.mockworker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Aggregate root for an image processing job.
 *
 * Invariants enforced here (never let a service reach in and flip the enum):
 *   - status transitions must follow {@link JobStatus#canTransitionTo(JobStatus)}
 *   - terminal states (COMPLETED/FAILED) are frozen
 *   - result is only set when moving to COMPLETED
 *   - failureReason is only set when moving to FAILED
 */
@Entity
@Table(
        name = "image_job",
        indexes = {
                @Index(name = "ux_image_job_client_request_key", columnList = "client_request_key", unique = true),
                @Index(name = "ix_image_job_status_created", columnList = "status, created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageJob {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    /**
     * Client-supplied idempotency key. Unique. Same key + same image returns the
     * same job; same key + different image is rejected at the service layer.
     */
    @Column(name = "client_request_key", length = 128, nullable = false, updatable = false)
    private String clientRequestKey;

    @Column(name = "image_url", length = 2048, nullable = false, updatable = false)
    private String imageUrl;

    /**
     * Stable hash of the request payload. Used to detect idempotency-key reuse with a
     * different payload (which we treat as a client bug, not a retry).
     */
    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private JobStatus status;

    /** The jobId returned by the Mock Worker. Null until we have successfully submitted. */
    @Column(name = "worker_job_id", length = 128)
    private String workerJobId;

    @Column(name = "result", length = 4096)
    private String result;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    /** Retry counter across submit/poll attempts. Used for backoff + dead-lettering. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** Earliest time at which a background worker may pick this row up again. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static ImageJob accept(String clientRequestKey, String imageUrl, String fingerprint, Instant now) {
        ImageJob j = new ImageJob();
        j.id = UUID.randomUUID().toString();
        j.clientRequestKey = clientRequestKey;
        j.imageUrl = imageUrl;
        j.requestFingerprint = fingerprint;
        j.status = JobStatus.PENDING;
        j.attemptCount = 0;
        j.nextAttemptAt = now;
        j.createdAt = now;
        j.updatedAt = now;
        return j;
    }

    public void markSubmitted(String workerJobId, Instant now) {
        requireTransition(JobStatus.IN_PROGRESS);
        if (workerJobId == null || workerJobId.isBlank()) {
            throw new InvalidJobStateException("workerJobId must be set when moving to IN_PROGRESS");
        }
        this.status = JobStatus.IN_PROGRESS;
        this.workerJobId = workerJobId;
        this.attemptCount = 0; // reset for the polling phase
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    public void markCompleted(String result, Instant now) {
        requireTransition(JobStatus.COMPLETED);
        this.status = JobStatus.COMPLETED;
        this.result = result;
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        requireTransition(JobStatus.FAILED);
        this.status = JobStatus.FAILED;
        this.failureReason = truncate(reason, 1024);
        this.updatedAt = now;
    }

    /**
     * Record a transient failure and schedule the next attempt. Does not change status.
     * Sets {@code failureReason} so operators can see what the last error was.
     * The caller has already decided that the error is retryable.
     */
    public void recordTransientFailure(Instant nextAttemptAt, String reason, Instant now) {
        if (status.isTerminal()) {
            throw new InvalidJobStateException("cannot record retry on terminal state " + status);
        }
        this.attemptCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.failureReason = truncate(reason, 1024);
        this.updatedAt = now;
    }

    /**
     * Advance the next-poll time after a PROCESSING response from the worker.
     * Unlike {@link #recordTransientFailure}, this clears {@code failureReason} because
     * the worker is making normal progress — this is not an error state.
     */
    public void recordProgress(Instant nextPollAt, Instant now) {
        if (status.isTerminal()) {
            throw new InvalidJobStateException("cannot advance poll on terminal state " + status);
        }
        if (status != JobStatus.IN_PROGRESS) {
            throw new InvalidJobStateException("recordProgress requires IN_PROGRESS, got " + status);
        }
        this.attemptCount++;
        this.nextAttemptAt = nextPollAt;
        this.failureReason = null;
        this.updatedAt = now;
    }

    private void requireTransition(JobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidJobStateException(
                    "illegal transition " + status + " -> " + next + " for job " + id);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
