package com.aethergate.domain.model;

/**
 * Ledger movement kinds. For every metered request: {@code RESERVE = SETTLE + RELEASE}.
 */
public enum EntryType {
    /** Credit added to the available balance. */
    TOP_UP,
    /** Pre-flight hold: available → reserved. */
    RESERVE,
    /** Actual cost consumed out of the hold. */
    SETTLE,
    /** Unused part of the hold returned to available. */
    RELEASE
}
