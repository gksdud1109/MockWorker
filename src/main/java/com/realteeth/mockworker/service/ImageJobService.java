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
 * Transactional entry point for job accept / lookup / list.
 *
 * Rules this service enforces:
 *   - accept is idempotent: same {@code idempotencyKey} + same payload → return existing job
 *   - idempotency-key reuse with a different payload is a 409, not a silent merge
 *   - NO external Mock Worker calls happen inside this service: the accept TX only
 *     inserts a PENDING row. The background {@code JobSubmitter} handles the external call.
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

        // Fast path: lookup existing
        var existing = repository.findByClientRequestKey(idempotencyKey);
        if (existing.isPresent()) {
            return verifyFingerprintOrThrow(existing.get(), fingerprint);
        }

        ImageJob job = ImageJob.accept(idempotencyKey, imageUrl, fingerprint, now);
        try {
            return repository.saveAndFlush(job);
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert with same idempotency key — resolve by reading the winner
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
