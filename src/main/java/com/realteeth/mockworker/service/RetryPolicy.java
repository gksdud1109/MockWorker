package com.realteeth.mockworker.service;

import com.realteeth.mockworker.client.MockWorkerException;
import com.realteeth.mockworker.domain.ImageJob;
import java.time.Duration;
import java.time.Instant;

/**
 * 재시도 여부 결정과 백오프 계산을 캡슐화.
 *
 * JobSubmitter 와 JobPoller 에 중복되어 있던 일시적/영구적/최대시도 분기 로직을 통합.
 * 재시도 전략 변경 시 한 곳만 수정하면 된다.
 *
 * @param initial     첫 재시도의 기준 대기 시간
 * @param ceiling     최대 대기 시간
 * @param maxAttempts 최대 시도 횟수 (null = 무제한)
 * @param phase       실패 사유 문자열에 사용할 단계 레이블 ("submit" 또는 "poll")
 */
public record RetryPolicy(Duration initial, Duration ceiling, Integer maxAttempts, String phase) {

    /**
     * 워커 예외 발생 후 정책을 작업에 적용. 작업 상태를 직접 변경하며 저장은 호출자 책임.
     *
     * @return FAILED 로 전이된 경우 true (호출자가 로그 처리를 다르게 할 수 있음)
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
