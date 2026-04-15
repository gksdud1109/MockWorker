package com.realteeth.mockworker.web;

import com.realteeth.mockworker.domain.ImageJob;
import com.realteeth.mockworker.service.ImageJobService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final ImageJobService service;

    public JobController(ImageJobService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateJobRequest body) {
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                // fallback: content-derived key. Two identical payloads will be deduped.
                // This is a safety net — clients in production SHOULD pass an explicit key.
                ? "auto-" + ImageJobServiceFingerprint.of(body.imageUrl())
                : idempotencyKey;
        ImageJob job = service.accept(key, body.imageUrl());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable("id") String id) {
        return JobResponse.from(service.get(id));
    }

    @GetMapping
    public JobListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ImageJob> p = service.list(PageRequest.of(page, Math.min(size, 100)));
        List<JobResponse> items = p.getContent().stream().map(JobResponse::from).toList();
        return new JobListResponse(items, p.getNumber(), p.getSize(), p.getTotalElements());
    }

    public record JobListResponse(List<JobResponse> items, int page, int size, long total) {}
}
