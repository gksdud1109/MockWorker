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
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PENDING 작업을 Mock Worker 에 제출하는 스케줄러.
 *
 * 트랜잭션 경계: 외부 HTTP 호출은 DB 트랜잭션 밖에서 수행.
 * 결과 저장 시에만 짧은 트랜잭션을 열어 워커 응답 대기 중 커넥션을 점유하지 않는다.
 *
 * 동시성: @Version 낙관적 락으로 행을 보호. 다른 인스턴스나 폴러가 먼저 수정한 경우
 * OptimisticLock 으로 저장 실패 → 다음 틱에서 재시도.
 *
 * 워커가 요청을 수신했지만 jobId 저장 전에 크래시가 발생하면 행이 PENDING 으로 남아
 * 재제출된다 (중복 워커 작업 가능). Mock Worker 에 멱등성 키가 없어 구조적으로 방지 불가.
 */
@Component
@Slf4j
public class JobSubmitter {

    private static final int BATCH_SIZE = 10;

    private final ImageJobRepository repository;
    private final MockWorkerClient client;
    private final RetryPolicy retryPolicy;
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
        this.retryPolicy = new RetryPolicy(
                props.submit().initialBackoff(),
                props.submit().maxBackoff(),
                props.submit().maxAttempts(),
                "submit");
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
        // 락 밖에서 재조회해 최신 상태를 가져옴; 저장은 @Version 으로 보호.
        ImageJob job = repository.findById(id).orElse(null);
        if (job == null || job.getStatus() != JobStatus.PENDING) return;

        WorkerJobSnapshot snapshot;
        try {
            snapshot = client.submit(job.getImageUrl());
        } catch (MockWorkerException e) {
            applyRetry(id, e);
            return;
        }

        // 워커가 작은 입력에 대해 동기적으로 COMPLETED/FAILED 를 응답할 수 있음.
        // OptimisticLockingFailureException 은 커밋 시점(람다 반환 후)에 발생하므로
        // 람다 내부가 아닌 executeWithoutResult 호출 전체를 감싸야 한다.
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

    private void applyRetry(String id, MockWorkerException e) {
        tx.executeWithoutResult(status -> {
            ImageJob fresh = repository.findById(id).orElse(null);
            if (fresh == null || fresh.getStatus() != JobStatus.PENDING) return;
            boolean failed = retryPolicy.apply(fresh, e, Instant.now(clock));
            repository.save(fresh);
            if (failed) {
                log.warn("job={} submit failed permanently: {}", id, fresh.getFailureReason());
            }
        });
    }
}
