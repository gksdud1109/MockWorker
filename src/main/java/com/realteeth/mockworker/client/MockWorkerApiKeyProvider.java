package com.realteeth.mockworker.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches and caches the API key issued by the Mock Worker.
 *
 * The key is loaded lazily on first use and cached in-memory. Callers can request
 * a forced refresh via {@link #refresh()} when they see a 401 from the worker.
 *
 * If a static key is configured via {@code mock-worker.api-key}, the provider
 * short-circuits and never calls issue-key. This is what the container integration
 * path uses so tests/reviewers don't need network on startup.
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

    public String refresh() {
        cached.set(null);
        return issueAndCache();
    }

    private synchronized String issueAndCache() {
        String existing = cached.get();
        if (existing != null) return existing;
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
            String key = body.get("apiKey").toString();
            cached.set(key);
            return key;
        } catch (MockWorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new MockWorkerException("failed to issue api key", e, true);
        }
    }
}
