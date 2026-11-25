package com.aethergate.service.team;

/** 410: unknown, used, replaced or expired invitation (deliberately indistinguishable). */
public class InvitationInvalidException extends RuntimeException {
    public InvitationInvalidException() {
        super("This invitation link is invalid or has expired; ask for a new one");
    }
}
