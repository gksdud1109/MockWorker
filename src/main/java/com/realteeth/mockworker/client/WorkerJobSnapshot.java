package com.realteeth.mockworker.client;

public record WorkerJobSnapshot(String jobId, WorkerJobStatus status, String result) {}
