package com.aethergate.service.tenant;

/** 403: the shared one-click demo workspace keeps its name and limit, since every visitor sees it. */
public class DemoReadOnlyException extends RuntimeException {
    public DemoReadOnlyException() {
        super("The shared demo workspace's settings are read-only; sign in to your own workspace to change them");
    }
}
