package com.manas.orderlifecycle.model;

import java.math.BigDecimal;

/**
 * One row of the append-only order_events log. `eventSeq` is the global
 * insertion order (the log's own primary key, never reused and never
 * updated), not a per-order counter -- reconstruction sorts by
 * (orderId, eventSeq) to recover each order's own chronology out of a
 * single interleaved stream.
 */
public final class OrderEvent {
    public final long eventSeq;
    public final long orderId;
    public final EventType eventType;
    public final long eventTsUs;   // microseconds since an arbitrary fixed epoch
    public final String symbol;
    public final Side side;
    public final BigDecimal orderQty;  // set on NEW/REPLACE, null otherwise
    public final BigDecimal fillQty;   // set on PARTIAL_FILL/FILL, null otherwise
    public final BigDecimal price;

    public OrderEvent(long eventSeq, long orderId, EventType eventType, long eventTsUs,
                       String symbol, Side side, BigDecimal orderQty, BigDecimal fillQty,
                       BigDecimal price) {
        this.eventSeq = eventSeq;
        this.orderId = orderId;
        this.eventType = eventType;
        this.eventTsUs = eventTsUs;
        this.symbol = symbol;
        this.side = side;
        this.orderQty = orderQty;
        this.fillQty = fillQty;
        this.price = price;
    }
}
