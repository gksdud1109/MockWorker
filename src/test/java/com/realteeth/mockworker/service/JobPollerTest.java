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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class JobPollerTest {

    @Autowired ImageJobService service;
    @Autowired ImageJobRepository repository;
    @Autowired JobSubmitter submitter;
    @Autowired JobPoller poller;
    @Autowired TransactionTemplate tx;

    @MockitoBean MockWorkerClient client;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private ImageJob acceptAndSubmit(String key, String url, String workerId) {
        when(client.submit(any()))
                .thenReturn(new WorkerJobSnapshot(workerId, WorkerJobStatus.PROCESSING, null));
        ImageJob job = service.accept(key, url);
        submitter.runOnce();
        return repository.findById(job.getId()).orElseThrow();
    }

    @Test
    void poll_completion_transitions_to_completed() {
        ImageJob job = acceptAndSubmit("k-ok", "https://img/a.png", "worker-1");
        when(client.fetch("worker-1"))
                .thenReturn(new WorkerJobSnapshot("worker-1", WorkerJobStatus.COMPLETED, "result-url"));

        // nextAttemptAt was bumped by markSubmitted to "now"; poller picks it immediately
        poller.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getResult()).isEqualTo("result-url");
    }

    @Test
    void worker_failed_transitions_to_failed_with_reason() {
        ImageJob job = acceptAndSubmit("k-fail", "https://img/a.png", "worker-2");
        when(client.fetch("worker-2"))
                .thenReturn(new WorkerJobSnapshot("worker-2", WorkerJobStatus.FAILED, null));

        poller.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getFailureReason()).contains("worker reported FAILED");
    }

    @Test
    void transient_poll_failure_keeps_job_in_progress_and_bumps_backoff() {
        ImageJob job = acceptAndSubmit("k-transient", "https://img/a.png", "worker-3");
        when(client.fetch("worker-3"))
                .thenThrow(new MockWorkerException("502 upstream", true));

        poller.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getFailureReason()).contains("502 upstream");
    }

    @Test
    void permanent_poll_failure_fails_job() {
        ImageJob job = acceptAndSubmit("k-perm", "https://img/a.png", "worker-4");
        when(client.fetch("worker-4"))
                .thenThrow(new MockWorkerException("404 gone", false));

        poller.runOnce();

        ImageJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getFailureReason()).contains("404 gone");
    }
}
