package com.realteeth.mockworker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobStatusTransitionTest {

    private final Instant t0 = Instant.parse("2026-04-15T10:00:00Z");

    @Test
    void allowed_transitions_pass() {
        assertThat(JobStatus.PENDING.canTransitionTo(JobStatus.IN_PROGRESS)).isTrue();
        assertThat(JobStatus.PENDING.canTransitionTo(JobStatus.FAILED)).isTrue();
        assertThat(JobStatus.IN_PROGRESS.canTransitionTo(JobStatus.COMPLETED)).isTrue();
        assertThat(JobStatus.IN_PROGRESS.canTransitionTo(JobStatus.FAILED)).isTrue();
    }

    @Test
    void terminal_states_are_frozen() {
        assertThat(JobStatus.COMPLETED.canTransitionTo(JobStatus.IN_PROGRESS)).isFalse();
        assertThat(JobStatus.COMPLETED.canTransitionTo(JobStatus.FAILED)).isFalse();
        assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.COMPLETED)).isFalse();
        assertThat(JobStatus.FAILED.canTransitionTo(JobStatus.IN_PROGRESS)).isFalse();
        assertThat(JobStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(JobStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void completed_cannot_be_reopened_via_entity() {
        ImageJob j = ImageJob.accept("k", "https://img/1.png", "fp", t0);
        j.markSubmitted("worker-1", t0);
        j.markCompleted("ok", t0);

        assertThatThrownBy(() -> j.markFailed("x", t0))
                .isInstanceOf(InvalidJobStateException.class);
        assertThatThrownBy(() -> j.markSubmitted("worker-2", t0))
                .isInstanceOf(InvalidJobStateException.class);
    }

    @Test
    void pending_skipping_to_completed_is_forbidden() {
        ImageJob j = ImageJob.accept("k", "https://img/1.png", "fp", t0);
        assertThatThrownBy(() -> j.markCompleted("ok", t0))
                .isInstanceOf(InvalidJobStateException.class);
    }

    @Test
    void transient_failure_does_not_mutate_terminal_jobs() {
        ImageJob j = ImageJob.accept("k", "https://img/1.png", "fp", t0);
        j.markSubmitted("worker-1", t0);
        j.markFailed("boom", t0);
        assertThatThrownBy(() -> j.recordTransientFailure(t0, "x", t0))
                .isInstanceOf(InvalidJobStateException.class);
    }
}
