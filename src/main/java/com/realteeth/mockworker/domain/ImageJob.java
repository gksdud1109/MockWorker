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
 * 이미지 처리 작업의 애그리거트 루트.
 *
 * 모든 상태 전이는 이 클래스 내부에서만 수행된다:
 *   - 상태 전이는 {@link JobStatus#canTransitionTo(JobStatus)} 를 따른다
 *   - 종료 상태(COMPLETED/FAILED)는 변경 불가
 *   - result 는 COMPLETED 전이 시에만 설정
 *   - failureReason 은 FAILED 전이 시에만 설정
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
     * 클라이언트가 제공하는 멱등성 키. 고유값.
     * 동일 키 + 동일 이미지 → 기존 작업 반환. 동일 키 + 다른 이미지 → 서비스 계층에서 409 반환.
     */
    @Column(name = "client_request_key", length = 128, nullable = false, updatable = false)
    private String clientRequestKey;

    @Column(name = "image_url", length = 2048, nullable = false, updatable = false)
    private String imageUrl;

    /**
     * 요청 페이로드의 해시값. 동일 멱등성 키로 다른 페이로드가 들어온 경우를 감지하는 데 사용.
     */
    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private JobStatus status;

    /** Mock Worker가 반환한 작업 ID. 제출 성공 전까지 null. */
    @Column(name = "worker_job_id", length = 128)
    private String workerJobId;

    @Column(name = "result", length = 4096)
    private String result;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    /** 제출/폴링 시도 횟수. 백오프 계산과 최대 재시도 판단에 사용. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 백그라운드 워커가 이 행을 다시 처리할 수 있는 가장 이른 시각. */
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
        this.attemptCount = 0; // 폴링 단계 진입 시 재시도 카운터 초기화
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
     * 일시적 오류를 기록하고 다음 재시도 시각을 설정. 상태는 변경하지 않는다.
     * {@code failureReason} 에 마지막 오류 내용을 저장해 운영자가 확인할 수 있게 한다.
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
     * 워커가 PROCESSING 을 응답한 경우 다음 폴링 시각을 갱신.
     * {@link #recordTransientFailure} 와 달리 {@code failureReason} 을 null 로 초기화한다.
     * 정상 진행 중이므로 오류 상태가 아님.
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
