package com.realteeth.mockworker.domain;

public class InvalidJobStateException extends RuntimeException {
    public InvalidJobStateException(String message) {
        super(message);
    }
}
