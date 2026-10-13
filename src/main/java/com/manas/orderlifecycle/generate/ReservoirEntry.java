package com.manas.orderlifecycle.generate;

import java.math.BigDecimal;

/** A sampled valid order, captured at generation time so the malformed-event
 * generator can build realistic defects that reference a real order (a
 * timestamp before that order's own NEW, a fill far beyond its declared
 * size) without having to keep all 3.1M orders' state in memory. */
public final class ReservoirEntry {
    public final long orderId;
    public final long newTsUs;
    public final BigDecimal qty;

    public ReservoirEntry(long orderId, long newTsUs, BigDecimal qty) {
        this.orderId = orderId;
        this.newTsUs = newTsUs;
        this.qty = qty;
    }
}
