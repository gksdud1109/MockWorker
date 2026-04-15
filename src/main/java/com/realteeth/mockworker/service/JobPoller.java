package com.realteeth.mockworker.service;

import com.realteeth.mockworker.client.MockWorkerClient;
import com.realteeth.mockworker.client.MockWorkerException;
import com.realteeth.mockworker.client.MockWorkerProperties;
import com.realteeth.mockworker.client.WorkerJobSnapshot;
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
 * IN_PROGRESS 상태 작업의 진행 상황을 Mock Worker 에 폴링.
 *
 * 데드라인: 작업이 {@code poll.deadline} 을 초과해 IN_PROGRESS 상태이면 FAILED 처리.
 * 데드라인은 {@code updatedAt} 기준으로 평가되므로 워커가 PROCESSING 을 계속 응답하는
 * 한 삭제되지 않는다.
 *
 * 일시적 폴링 실패는 지수 백오프로 {@code nextAttemptAt} 을 연장.
 * 영구 실패(429 제외 4xx)는 즉시 FAILED 처리.
 */
@Component
@Slf4j
public class JobPoller {

    private static final int BATCH_SIZE = 20;

    private final ImageJobRepository repository;
    private final MockWorkerClient client;
    private final MockWorkerProperties props;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JobPoller(
            ImageJobRepository repository,
            MockWorkerClient client,
            MockWorkerProperties props,
            TransactionTemplate tx,
            Clock clock) {
        this.repository = repository;
        this.client = client;
        this.props = props;
        // 폴링은 maxAttempts 없음 — 데드라인 기반으로 만료
        this.retryPolicy = new RetryPolicy(
                props.poll().initialInterval(),
                props.poll().maxInterval(),
                null,
                "poll");
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mock-worker.poll.scheduler-delay-ms:1000}")
    public void runOnce() {
        List<String> dueIds = tx.execute(status ->
                repository.findDueByStatus(JobStatus.IN_PROGRESS, Instant.now(clock), PageRequest.of(0, BATCH_SIZE))
                        .stream().map(ImageJob::getId).toList());
        if (dueIds == null || dueIds.isEmpty()) return;

        for (String id : dueIds) {
            try {
                pollOne(id);
            } catch (Exception e) {
                log.warn("poll loop caught unexpected error for job={}", id, e);
            }
        }
    }

    private void pollOne(String id) {
        ImageJob job = repository.findById(id).orElse(null);
        if (job == null || job.getStatus() != JobStatus.IN_PROGRESS) return;

        if (job.getWorkerJobId() == null) {
            log.error("job={} is IN_PROGRESS without workerJobId — invariant violation", id);
            return;
        }

        // 데드라인 체크 — 외부 호출 전에 수행해 불필요한 작업 방지.
        Instant now = Instant.now(clock);
        if (props.poll().deadline() != null
                && job.getUpdatedAt().plus(props.poll().deadline()).isBefore(now)) {
            tx.executeWithoutResult(status -> {
                ImageJob fresh = repository.findById(id).orElse(null);
                if (fresh == null || fresh.getStatus() != JobStatus.IN_PROGRESS) return;
                fresh.markFailed("poll deadline exceeded", Instant.now(clock));
                repository.save(fresh);
                log.warn("job={} poll deadline exceeded, marking FAILED", id);
            });
            return;
        }

        WorkerJobSnapshot snapshot;
        try {
            snapshot = client.fetch(job.getWorkerJobId());
        } catch (MockWorkerException e) {
            applyRetry(id, e);
            return;
        }

        // OptimisticLockingFailureException 은 커밋 시점(람다 반환 후)에 발생하므로
        // 람다 내부가 아닌 executeWithoutResult 호출 전체를 감싸야 한다.
        try {
            tx.executeWithoutResult(status -> {
                ImageJob fresh = repository.findById(id).orElse(null);
                if (fresh == null || fresh.getStatus() != JobStatus.IN_PROGRESS) return;
                Instant t = Instant.now(clock);
                switch (snapshot.status()) {
                    case COMPLETED -> fresh.markCompleted(snapshot.result(), t);
                    case FAILED -> fresh.markFailed("worker reported FAILED", t);
                    case PROCESSING -> {
                        Duration backoff = BackoffCalculator.next(
                                props.poll().initialInterval(),
                                props.poll().maxInterval(),
                                fresh.getAttemptCount());
                        fresh.recordProgress(t.plus(backoff), t);
                    }
                }
                repository.save(fresh);
            });
        } catch (ObjectOptimisticLockingFailureException race) {
            log.info("poll concurrent update for job={}, will retry next tick", id);
        }
    }

    private void applyRetry(String id, MockWorkerException e) {
        tx.executeWithoutResult(status -> {
            ImageJob fresh = repository.findById(id).orElse(null);
            if (fresh == null || fresh.getStatus() != JobStatus.IN_PROGRESS) return;
            boolean failed = retryPolicy.apply(fresh, e, Instant.now(clock));
            repository.save(fresh);
            if (failed) {
                log.warn("job={} poll failed permanently: {}", id, fresh.getFailureReason());
            }
        });
    }
}
