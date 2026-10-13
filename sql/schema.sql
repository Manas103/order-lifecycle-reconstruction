-- Append-only order-event log plus the two tables reconstruction populates.
-- Nothing in this project issues an UPDATE or DELETE against order_events;
-- that is enforced by code review (grep the source for "UPDATE order_events"
-- or "DELETE FROM order_events" and find nothing), and
-- Tests#testAppendOnlyRowCountNeverChanges additionally asserts the row
-- count before and after a full reconstruction run is identical.

CREATE TABLE order_events (
    event_seq   BIGINT PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    event_type  VARCHAR(20) NOT NULL,
    event_ts_us BIGINT NOT NULL,
    symbol      VARCHAR(16) NOT NULL,
    side        VARCHAR(4) NOT NULL,
    order_qty   NUMERIC(18,4),
    fill_qty    NUMERIC(18,4),
    price       NUMERIC(18,4) NOT NULL
);

-- Turns the reconstruction pass's ORDER BY order_id, event_seq into an index
-- scan instead of a sort over the whole 12M+ row table.
CREATE INDEX idx_order_events_order_seq ON order_events(order_id, event_seq);

CREATE TABLE correction_queue (
    event_seq  BIGINT PRIMARY KEY REFERENCES order_events(event_seq),
    order_id   BIGINT NOT NULL,
    reason     VARCHAR(32) NOT NULL,
    queued_for VARCHAR(8) NOT NULL
);

CREATE TABLE lifecycle_summary (
    order_id           BIGINT PRIMARY KEY,
    symbol             VARCHAR(16) NOT NULL,
    side               VARCHAR(4) NOT NULL,
    original_qty       NUMERIC(18,4) NOT NULL,
    size_category      VARCHAR(24) NOT NULL,
    new_ts_us          BIGINT NOT NULL,
    terminal_state     VARCHAR(32) NOT NULL,
    total_filled_qty   NUMERIC(18,4) NOT NULL,
    first_fill_ts_us   BIGINT,
    time_to_exec_ms    DOUBLE PRECISION
);
