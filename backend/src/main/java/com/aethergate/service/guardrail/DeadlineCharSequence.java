package com.aethergate.service.guardrail;

/**
 * A CharSequence that aborts regex matching once a time budget is spent. java.util.regex has no timeout, but
 * every step of the matcher calls {@link #charAt}, so checking the clock there (every 1,024 reads) bounds even
 * catastrophic backtracking such as {@code (a+)+$} on a long input.
 */
final class DeadlineCharSequence implements CharSequence {

    /** Thrown when the budget is exhausted; the engine turns it into a fail-closed BLOCK. */
    static final class TimeoutException extends RuntimeException {
        TimeoutException() {
            super("guardrail evaluation exceeded its time budget", null, false, false);
        }
    }

    private final CharSequence inner;
    private final long deadline;
    private int reads;

    DeadlineCharSequence(CharSequence inner, long budgetNanos) {
        this(inner, System.nanoTime() + budgetNanos, 0);
    }

    private DeadlineCharSequence(CharSequence inner, long deadline, int reads) {
        this.inner = inner;
        this.deadline = deadline;
        this.reads = reads;
    }

    @Override
    public char charAt(int index) {
        if ((++reads & 1023) == 0 && System.nanoTime() > deadline) {
            throw new TimeoutException();
        }
        return inner.charAt(index);
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new DeadlineCharSequence(inner.subSequence(start, end), deadline, reads);
    }

    @Override
    public String toString() {
        return inner.toString();
    }
}
