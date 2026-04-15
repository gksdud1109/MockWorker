package com.realteeth.mockworker.domain;

import java.util.Map;
import java.util.Set;

public enum JobStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = Map.of(
            PENDING, Set.of(IN_PROGRESS, FAILED),
            IN_PROGRESS, Set.of(COMPLETED, FAILED),
            COMPLETED, Set.of(),
            FAILED, Set.of()
    );

    public boolean canTransitionTo(JobStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
