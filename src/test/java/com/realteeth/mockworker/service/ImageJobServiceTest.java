package com.realteeth.mockworker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.domain.ImageJobRepository;
import com.realteeth.mockworker.domain.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ImageJobServiceTest {

    @Autowired ImageJobService service;
    @Autowired ImageJobRepository repository;

    @Test
    void accept_persists_pending_job() {
        ImageJob job = service.accept("key-1", "https://img/a.png");
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(repository.findById(job.getId())).isPresent();
    }

    @Test
    void duplicate_idempotency_key_returns_same_job() {
        ImageJob first = service.accept("key-dup", "https://img/a.png");
        ImageJob second = service.accept("key-dup", "https://img/a.png");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void idempotency_key_reuse_with_different_payload_is_rejected() {
        service.accept("key-x", "https://img/a.png");
        assertThatThrownBy(() -> service.accept("key-x", "https://img/b.png"))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
