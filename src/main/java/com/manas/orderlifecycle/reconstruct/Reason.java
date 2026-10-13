package com.manas.orderlifecycle.reconstruct;

/** Why one event was routed to the correction queue instead of applied. */
public enum Reason {
    UNLINKABLE_ORDER_ID,
    DUPLICATE_NEW,
    NEGATIVE_OR_ZERO_QTY,
    TIMESTAMP_BEFORE_NEW,
    FILL_EXCEEDS_DECLARED_QTY
}
