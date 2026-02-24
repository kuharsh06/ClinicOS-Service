package com.clinicos.service.exception;

/**
 * Exception thrown when an operation conflicts with the current state.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
