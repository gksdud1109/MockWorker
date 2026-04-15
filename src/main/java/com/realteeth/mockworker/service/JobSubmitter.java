package com.realteeth.mockworker.service;

import com.realteeth.mockworker.client.MockWorkerClient;
import com.realteeth.mockworker.client.MockWorkerException;
import com.realteeth.mockworker.client.MockWorkerProperties;
import com.realteeth.mockworker.client.WorkerJobSnapshot;
import com.realteeth.mockworker.client.WorkerJobStatus;
import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.domain.ImageJobRepository;
import com.realteeth.mockworker.domain.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled background worker that takes PENDING jobs and submits them to the Mock Worker.
 *
 * Transaction boundary: the external HTTP call is made OUTSIDE any DB transaction.
 * We open a short TX only to persist the result (or the retry schedule). This keeps
 * DB connections free while the worker is slow.
 *
 * Concurrency: jobs are guarded by JPA @Version. If another instance (or the poller)
 * updated the same row, we fail the save with OptimisticLock and just move on — the
 * next tick will find the row again.
 *
 * On submit crash after the worker received the request but before we persisted its
 * jobId: the row stays in PENDING and will be submitted again → duplicate worker job.
 * This is the at-least-once boundary documented in the README. Mock Worker has no
 * idempotency key so we cannot structurally prevent this.
 */
@Component
@Slf4j
public class JobSubmitter {

    private static final int BATCH_SIZE = 10;

    private final ImageJobRepository repository;
    private final MockWorkerClient client;
    private final MockWorkerProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JobSubmitter(
            ImageJobRepository repository,
            MockWorkerClient client,
            MockWorkerProperties props,
            TransactionTemplate tx,
            Clock clock) {
        this.repository = repository;
        this.client = client;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mock-worker.submit.scheduler-delay-ms:1000}")
    public void runOnce() {
        List<String> dueIds = tx.execute(status ->
                repository.findDueByStatus(JobStatus.PENDING, Instant.now(clock), PageRequest.of(0, BATCH_SIZE))
                        .stream().map(ImageJob::getId).toList());
        if (dueIds == null || dueIds.isEmpty()) return;

        for (String id : dueIds) {
            try {
                submitOne(id);
            } catch (Exception e) {
                log.warn("submit loop caught unexpected error for job={}", id, e);
            }
        }
    }

    private void submitOne(String id) {
        // Re-read outside lock so we have a fresh snapshot; the save below is @Version-guarded.
        ImageJob job = repository.findById(id).orElse(null);
        if (job == null || job.getStatus() != JobStatus.PENDING) return;

        WorkerJobSnapshot snapshot;
        try {
            snapshot = client.submit(job.getImageUrl());
        } catch (MockWorkerException e) {
            handleSubmitFailure(id, e);
            return;
        }

        // Worker may respond synchronously with COMPLETED/FAILED for small inputs.
        // OptimisticLockingFailureException is thrown by Hibernate at commit time (after the
        // lambda returns), so the catch must wrap the executeWithoutResult call, not its body.
        try {
            tx.executeWithoutResult(status -> {
                ImageJob fresh = repository.findById(id).orElse(null);
                if (fresh == null || fresh.getStatus() != JobStatus.PENDING) return;
                Instant now = Instant.now(clock);
                fresh.markSubmitted(snapshot.jobId(), now);
                if (snapshot.status() == WorkerJobStatus.COMPLETED) {
                    fresh.markCompleted(snapshot.result(), now);
                } else if (snapshot.status() == WorkerJobStatus.FAILED) {
                    fresh.markFailed("worker reported FAILED at submit", now);
                }
                repository.save(fresh);
            });
        } catch (ObjectOptimisticLockingFailureException race) {
            log.info("submit concurrent update for job={}, will retry next tick", id);
        }
    }

    private void handleSubmitFailure(String id, MockWorkerException e) {
        tx.executeWithoutResult(status -> {
            ImageJob fresh = repository.findById(id).orElse(null);
            if (fresh == null || fresh.getStatus() != JobStatus.PENDING) return;
            Instant now = Instant.now(clock);

            if (!e.isTransient()) {
                fresh.markFailed("submit permanent failure: " + e.getMessage(), now);
                repository.save(fresh);
                return;
            }
            int nextAttempt = fresh.getAttemptCount() + 1;
            if (nextAttempt >= props.submit().maxAttempts()) {
                fresh.markFailed("submit max attempts exceeded: " + e.getMessage(), now);
                repository.save(fresh);
                return;
            }
            Duration backoff = BackoffCalculator.next(
                    props.submit().initialBackoff(),
                    props.submit().maxBackoff(),
                    nextAttempt);
            fresh.recordTransientFailure(now.plus(backoff), e.getMessage(), now);
            repository.save(fresh);
        });
    }
}
