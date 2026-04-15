package com.realteeth.mockworker.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mock-worker")
public record MockWorkerProperties(
        String baseUrl,
        String candidateName,
        String email,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Submit submit,
        Poll poll
) {
    public record Submit(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {}
    public record Poll(Duration initialInterval, Duration maxInterval, Duration deadline) {}
}
