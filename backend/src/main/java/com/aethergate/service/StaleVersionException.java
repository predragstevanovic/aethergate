package com.aethergate.service;

/** 412: the client edited an old version of the resource (its If-Match no longer matches the ETag). */
public class StaleVersionException extends RuntimeException {

    private final long currentVersion;

    public StaleVersionException(long expected, long currentVersion) {
        super("The resource was modified by someone else (you edited version " + expected + ", current is " + currentVersion
                + "); reload and retry");
        this.currentVersion = currentVersion;
    }

    public long getCurrentVersion() { return currentVersion; }
}
