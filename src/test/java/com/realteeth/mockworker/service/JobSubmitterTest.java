package com.realteeth.mockworker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.realteeth.mockworker.client.MockWorkerClient;
import com.realteeth.mockworker.client.MockWorkerException;
import com.realteeth.mockworker.client.WorkerJobSnapshot;
import com.realteeth.mockworker.client.WorkerJobStatus;
import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.domain.ImageJobRepository;
import com.realteeth.mockworker.domain.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class JobSubmitterTest {

    @Autowired ImageJobService service;
    @Autowired ImageJobRepository repository;
    @Autowired JobSubmitter submitter;

    @MockitoBean MockWorkerClient client;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void successful_submit_moves_job_to_in_progress() {
        when(client.submit(any())).thenReturn(
                new WorkerJobSnapshot("worker-1", WorkerJobStatus.PROCESSING, null));

        ImageJob job = service.accept("k-ok", "https://img/a.png");
        submitter.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
        assertThat(reloaded.getWorkerJobId()).isEqualTo("worker-1");
    }

    @Test
    void transient_failure_schedules_retry_and_stays_pending() {
        when(client.submit(any()))
                .thenThrow(new MockWorkerException("503 upstream", true));

        ImageJob job = service.accept("k-retry", "https://img/a.png");
        submitter.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isAfter(job.getNextAttemptAt());
        assertThat(reloaded.getFailureReason()).contains("503 upstream");
    }

    @Test
    void permanent_failure_fails_job_immediately() {
        when(client.submit(any()))
                .thenThrow(new MockWorkerException("400 bad request", false));

        ImageJob job = service.accept("k-perm", "https://img/a.png");
        submitter.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getFailureReason()).contains("400 bad request");
    }
}
