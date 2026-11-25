package com.aethergate.service.tenant;

import com.aethergate.domain.model.MemberRole;

/** 403: the member's role is below what the operation requires. */
public class InsufficientRoleException extends RuntimeException {

    private final MemberRole required;
    private final MemberRole actual;

    public InsufficientRoleException(MemberRole required, MemberRole actual) {
        super("This action requires the " + label(required) + " role; you are " + label(actual));
        this.required = required;
        this.actual = actual;
    }

    public InsufficientRoleException(String message, MemberRole required, MemberRole actual) {
        super(message);
        this.required = required;
        this.actual = actual;
    }

    private static String label(MemberRole r) {
        return r.name().charAt(0) + r.name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    public MemberRole getRequired() { return required; }
    public MemberRole getActual() { return actual; }
}
