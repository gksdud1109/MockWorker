package com.realteeth.mockworker.client;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Slf4j
public class HttpMockWorkerClient implements MockWorkerClient {

    private final RestClient restClient;
    private final MockWorkerApiKeyProvider apiKeyProvider;

    public HttpMockWorkerClient(RestClient mockWorkerRestClient, MockWorkerApiKeyProvider apiKeyProvider) {
        this.restClient = mockWorkerRestClient;
        this.apiKeyProvider = apiKeyProvider;
    }

    @Override
    public WorkerJobSnapshot submit(String imageUrl) {
        return call(() -> {
            Map<?, ?> body = restClient.post()
                    .uri("/process")
                    .header("X-API-KEY", apiKeyProvider.get())
                    .body(Map.of("imageUrl", imageUrl))
                    .retrieve()
                    .body(Map.class);
            return toSnapshot(body);
        }, "submit");
    }

    @Override
    public WorkerJobSnapshot fetch(String workerJobId) {
        return call(() -> {
            Map<?, ?> body = restClient.get()
                    .uri("/process/{id}", workerJobId)
                    .header("X-API-KEY", apiKeyProvider.get())
                    .retrieve()
                    .body(Map.class);
            return toSnapshot(body);
        }, "fetch");
    }

    private WorkerJobSnapshot call(java.util.function.Supplier<WorkerJobSnapshot> op, String label) {
        try {
            return op.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode code = e.getStatusCode();
            if (code.value() == 401) {
                // key may have expired; refresh once and retry
                log.warn("mock-worker returned 401 on {}, refreshing api key", label);
                apiKeyProvider.refresh();
                try {
                    return op.get();
                } catch (Exception inner) {
                    throw classify(inner, label);
                }
            }
            throw classify(e, label);
        } catch (ResourceAccessException e) {
            // network/IO/timeout
            throw new MockWorkerException("mock-worker " + label + " network failure", e, true);
        } catch (Exception e) {
            throw new MockWorkerException("mock-worker " + label + " unexpected failure", e, true);
        }
    }

    private MockWorkerException classify(Exception e, String label) {
        if (e instanceof RestClientResponseException rce) {
            int s = rce.getStatusCode().value();
            boolean retryable = s == 429 || s >= 500;
            return new MockWorkerException(
                    "mock-worker " + label + " http " + s + ": " + rce.getResponseBodyAsString(),
                    rce, retryable);
        }
        if (e instanceof ResourceAccessException) {
            return new MockWorkerException("mock-worker " + label + " network failure", e, true);
        }
        return new MockWorkerException("mock-worker " + label + " unexpected failure", e, true);
    }

    private WorkerJobSnapshot toSnapshot(Map<?, ?> body) {
        if (body == null) {
            throw new MockWorkerException("mock-worker returned empty body", false);
        }
        Object id = body.get("jobId");
        Object status = body.get("status");
        Object result = body.get("result");
        if (id == null || status == null) {
            throw new MockWorkerException("mock-worker returned malformed body: " + body, false);
        }
        WorkerJobStatus parsed;
        try {
            parsed = WorkerJobStatus.valueOf(status.toString());
        } catch (IllegalArgumentException ex) {
            throw new MockWorkerException("unknown worker status: " + status, false);
        }
        return new WorkerJobSnapshot(id.toString(), parsed, result == null ? null : result.toString());
    }
}
