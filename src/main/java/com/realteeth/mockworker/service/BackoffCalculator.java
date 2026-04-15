package com.realteeth.mockworker.service;

import java.time.Duration;

final class BackoffCalculator {
    private BackoffCalculator() {}

    /**
     * 상한값이 있는 지수 백오프 계산.
     *
     * @param initial   첫 번째 재시도의 기준 대기 시간
     * @param max       최대 대기 시간 (초과 불가)
     * @param doneCount 이미 완료된 시도 횟수 (0 = 아직 시도 없음 → 첫 재시도)
     *
     * 매핑: doneCount=0 → initial, doneCount=1 → initial×2, doneCount=2 → initial×4, ...
     * 호출 측에서 {@code fresh.getAttemptCount()} 를 그대로 넘기면 된다.
     */
    static Duration next(Duration initial, Duration max, int doneCount) {
        if (doneCount < 0) doneCount = 0;
        // long 오버플로 방지를 위해 shift 를 20으로 제한
        int shift = Math.min(doneCount, 20);
        long millis = initial.toMillis() * (1L << shift);
        long capped = Math.min(millis, max.toMillis());
        return Duration.ofMillis(capped);
    }
}
