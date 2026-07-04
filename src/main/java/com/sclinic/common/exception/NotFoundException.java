package com.sclinic.common.exception;

/**
 * Thrown when a requested entity does not exist. Mapped to HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
