package com.realteeth.mockworker.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches and caches the API key issued by the Mock Worker.
 *
 * Threading contract:
 *   - {@link #get()}: fast path via AtomicReference; falls back to synchronized issue-key call.
 *   - {@link #refresh()}: always issues a new key regardless of cache state.
 *     Runs fully inside the same synchronized block as {@link #issueAndCache()} to prevent
 *     a concurrent {@code get()} from re-caching a stale key between the clear and re-fetch.
 *
 * If a static key is configured via {@code mock-worker.api-key}, the provider
 * short-circuits and never calls issue-key.
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
     * Force-fetch a new key and update the cache.
     * Runs under the same lock as {@link #issueAndCache()} so that no concurrent thread
     * can observe the cleared cache and race to fetch before us.
     */
    public synchronized String refresh() {
        cached.set(null);
        String key = fetchFromRemote();
        cached.set(key);
        return key;
    }

    private synchronized String issueAndCache() {
        // Double-check: another thread may have already fetched while we were waiting for the lock.
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
