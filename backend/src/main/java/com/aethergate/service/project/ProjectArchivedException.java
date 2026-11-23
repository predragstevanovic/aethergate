package com.aethergate.service.project;

/** 409: the project is archived, so it cannot accept traffic, keys or edits until it is restored. */
public class ProjectArchivedException extends RuntimeException {
    public ProjectArchivedException(String projectName) {
        super("Project '" + projectName + "' is archived; restore it first");
    }
}
