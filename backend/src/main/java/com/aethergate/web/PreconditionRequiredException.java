package com.aethergate.web;

/** 428: a conditional write was sent without If-Match, so a lost update could not be ruled out. */
class PreconditionRequiredException extends RuntimeException {
    PreconditionRequiredException() {
        super("Send If-Match with the project's ETag (its version) to update it");
    }
}
