package com.manas.orderlifecycle.reconstruct;

public enum TerminalState {
    FILLED,
    PARTIALLY_FILLED_CANCELED,
    CANCELED,
    REJECTED,
    /** No terminal event was ever recognized for this order. Not expected
     * to occur for any of the 3.1M generated lifecycles (every one's own
     * genuine terminal event is always present in its group; only extra,
     * malformed siblings get filtered out) but handled rather than assumed
     * away, since this is reconstruction code reading an append-only log it
     * does not otherwise control the contents of. */
    OPEN
}
