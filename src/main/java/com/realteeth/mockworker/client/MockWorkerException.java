package com.realteeth.mockworker.client;

/**
 * {@link MockWorkerClient} 호출 실패 시 던지는 예외.
 *
 * {@code transient_} 값으로 재시도 여부를 결정한다:
 *   - true: 네트워크 I/O 오류, 5xx, 429, 타임아웃 — 재시도 가능
 *   - false: 4xx(429 제외), 응답 파싱 실패 — 재시도해도 해결 안 됨
 */
public class MockWorkerException extends RuntimeException {
    private final boolean transient_;

    public MockWorkerException(String message, boolean isTransient) {
        super(message);
        this.transient_ = isTransient;
    }

    public MockWorkerException(String message, Throwable cause, boolean isTransient) {
        super(message, cause);
        this.transient_ = isTransient;
    }

    public boolean isTransient() {
        return transient_;
    }
}
