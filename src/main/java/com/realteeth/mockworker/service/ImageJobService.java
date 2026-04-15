package com.realteeth.mockworker.service;

import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.domain.ImageJobRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 등록/조회/목록의 트랜잭션 진입점.
 *
 * 이 서비스가 보장하는 규칙:
 *   - accept 는 멱등성 보장: 동일 {@code idempotencyKey} + 동일 페이로드 → 기존 작업 반환
 *   - 동일 키로 다른 페이로드가 오면 409 반환 (조용한 병합 없음)
 *   - 외부 Mock Worker 호출은 이 서비스에서 발생하지 않음: accept 는 PENDING 행만 삽입하고,
 *     실제 외부 호출은 백그라운드 {@code JobSubmitter} 가 담당
 */
@Service
@Slf4j
public class ImageJobService {

    private final ImageJobRepository repository;
    private final Clock clock;

    public ImageJobService(ImageJobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ImageJob accept(String idempotencyKey, String imageUrl) {
        String fingerprint = fingerprint(imageUrl);
        Instant now = Instant.now(clock);

        // 기존 작업 조회 (빠른 경로)
        var existing = repository.findByClientRequestKey(idempotencyKey);
        if (existing.isPresent()) {
            return verifyFingerprintOrThrow(existing.get(), fingerprint);
        }

        ImageJob job = ImageJob.accept(idempotencyKey, imageUrl, fingerprint, now);
        try {
            return repository.saveAndFlush(job);
        } catch (DataIntegrityViolationException race) {
            // 동일 멱등성 키로 동시 삽입 발생 시 — 승자 행을 읽어서 반환
            return repository.findByClientRequestKey(idempotencyKey)
                    .map(existingJob -> verifyFingerprintOrThrow(existingJob, fingerprint))
                    .orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public ImageJob get(String jobId) {
        return repository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public Page<ImageJob> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    private ImageJob verifyFingerprintOrThrow(ImageJob existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "idempotency key reused with a different payload: " + existing.getClientRequestKey());
        }
        return existing;
    }

    public static String fingerprint(String imageUrl) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("v1|" + imageUrl).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
