package com.manas.orderlifecycle.model;

/**
 * The five event kinds an order's append-only log can contain. There is no
 * sixth "AMEND quantity down" type distinct from REPLACE -- a real venue's
 * order-entry protocol folds price and quantity changes into one Replace,
 * and this project follows that rather than inventing a finer-grained type
 * nothing downstream would use differently.
 */
public enum EventType {
    NEW,
    REPLACE,
    CANCEL,
    PARTIAL_FILL,
    FILL,
    REJECT
}
