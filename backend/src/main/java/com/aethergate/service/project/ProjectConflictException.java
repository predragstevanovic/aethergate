package com.aethergate.service.project;

/** 409: the change is valid in shape but conflicts with current state (duplicate name, billing history, limits). */
public class ProjectConflictException extends RuntimeException {
    public ProjectConflictException(String message) {
        super(message);
    }
}
