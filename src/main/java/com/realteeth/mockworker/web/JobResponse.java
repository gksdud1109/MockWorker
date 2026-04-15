package com.realteeth.mockworker.web;

import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.domain.JobStatus;
import java.time.Instant;

public record JobResponse(
        String id,
        JobStatus status,
        String imageUrl,
        String result,
        String failureReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static JobResponse from(ImageJob j) {
        return new JobResponse(
                j.getId(),
                j.getStatus(),
                j.getImageUrl(),
                j.getResult(),
                j.getFailureReason(),
                j.getAttemptCount(),
                j.getCreatedAt(),
                j.getUpdatedAt()
        );
    }
}
