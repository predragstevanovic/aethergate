package com.aethergate.service.tenant;

/** 403: the user asked for a workspace they are not (or no longer) a member of. */
public class WorkspaceAccessDeniedException extends RuntimeException {
    public WorkspaceAccessDeniedException() {
        super("You are not a member of this workspace");
    }
}
