package com.realteeth.mockworker.web;

import com.realteeth.mockworker.domain.InvalidJobStateException;
import com.realteeth.mockworker.service.IdempotencyConflictException;
import com.realteeth.mockworker.service.JobNotFoundException;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(JobNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(IdempotencyConflictException e) {
        return build(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidJobStateException.class)
    public ResponseEntity<Map<String, Object>> invalidState(InvalidJobStateException e) {
        return build(HttpStatus.CONFLICT, "invalid_state", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        return build(HttpStatus.BAD_REQUEST, "validation_failed",
                e.getBindingResult().getAllErrors().stream()
                        .findFirst().map(er -> er.getDefaultMessage()).orElse("invalid request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal error");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", code,
                "message", message == null ? "" : message
        ));
    }
}
