package com.realteeth.mockworker.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mock Worker 가 발급한 API 키를 캐시.
 *
 * 스레드 안전 계약:
 *   - {@link #get()}: AtomicReference 로 빠른 경로 조회; 없으면 synchronized 발급 호출.
 *   - {@link #refresh()}: 캐시 상태와 무관하게 항상 새 키 발급.
 *     {@link #issueAndCache()} 와 동일한 락을 사용해 clear → re-fetch 사이의 경쟁 조건 방지.
 *
 * {@code mock-worker.api-key} 가 설정된 경우 원격 발급을 생략한다.
 */
@Component
@Slf4j
public class MockWorkerApiKeyProvider {

    private final MockWorkerProperties props;
    private final RestClient restClient;
    private final AtomicReference<String> cached = new AtomicReference<>();

    public MockWorkerApiKeyProvider(MockWorkerProperties props, RestClient mockWorkerRestClient) {
        this.props = props;
        this.restClient = mockWorkerRestClient;
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            cached.set(props.apiKey());
        }
    }

    public String get() {
        String k = cached.get();
        if (k != null) return k;
        return issueAndCache();
    }

    /**
     * 새 키를 강제 발급하고 캐시를 갱신.
     * {@link #issueAndCache()} 와 동일한 락을 사용해 clear → re-fetch 사이에 다른 스레드가 끼어드는 것을 방지.
     */
    public synchronized String refresh() {
        cached.set(null);
        String key = fetchFromRemote();
        cached.set(key);
        return key;
    }

    private synchronized String issueAndCache() {
        // 더블 체크: 락 대기 중 다른 스레드가 이미 발급했을 수 있음.
        String existing = cached.get();
        if (existing != null) return existing;
        String key = fetchFromRemote();
        cached.set(key);
        return key;
    }

    private String fetchFromRemote() {
        log.info("issuing mock-worker api key for candidate={}", props.candidateName());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri("/auth/issue-key")
                    .body(Map.of(
                            "candidateName", props.candidateName(),
                            "email", props.email()
                    ))
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("apiKey") == null) {
                throw new MockWorkerException("issue-key returned empty body", false);
            }
            return body.get("apiKey").toString();
        } catch (MockWorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new MockWorkerException("failed to issue api key", e, true);
        }
    }
}
