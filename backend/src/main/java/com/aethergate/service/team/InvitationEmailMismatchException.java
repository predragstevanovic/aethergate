package com.aethergate.service.team;

/** 403: an invitation link is a bearer token, but only the invited e-mail address may redeem it. */
public class InvitationEmailMismatchException extends RuntimeException {
    public InvitationEmailMismatchException(String invited) {
        super("This invitation was sent to " + mask(invited) + "; sign in with that address to accept it");
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "another address" : email.charAt(0) + "***" + email.substring(at);
    }
}
