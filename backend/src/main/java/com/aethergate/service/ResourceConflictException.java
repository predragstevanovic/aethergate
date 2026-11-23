package com.aethergate.service;

/** 409: the change is valid in shape but conflicts with current state (duplicate name, history, limits). */
public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
