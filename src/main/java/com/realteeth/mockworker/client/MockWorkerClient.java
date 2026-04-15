package com.realteeth.mockworker.client;

public interface MockWorkerClient {
    /** Submit an image for processing. Returns the worker-assigned jobId + status. */
    WorkerJobSnapshot submit(String imageUrl);

    /** Fetch the current status of a worker job. */
    WorkerJobSnapshot fetch(String workerJobId);
}
