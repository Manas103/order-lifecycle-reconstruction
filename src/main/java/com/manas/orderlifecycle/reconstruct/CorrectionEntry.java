package com.manas.orderlifecycle.reconstruct;

public final class CorrectionEntry {
    public final long eventSeq;
    public final long orderId;
    public final Reason reason;

    public CorrectionEntry(long eventSeq, long orderId, Reason reason) {
        this.eventSeq = eventSeq;
        this.orderId = orderId;
        this.reason = reason;
    }
}
