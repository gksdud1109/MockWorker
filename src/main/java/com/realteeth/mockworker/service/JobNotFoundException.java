package com.realteeth.mockworker.service;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String id) {
        super("job not found: " + id);
    }
}
