package com.manas.orderlifecycle.db;

import com.manas.orderlifecycle.model.OrderEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Batches inserts into order_events. This is the only class in the project
 * that writes to that table, and the only statement it ever prepares is an
 * INSERT -- there is no UPDATE or DELETE against order_events anywhere in
 * this source tree, which is what "append-only" means operationally here.
 */
public final class EventLogWriter implements AutoCloseable {
    private static final String INSERT_SQL =
        "INSERT INTO order_events (event_seq, order_id, event_type, event_ts_us, symbol, side, order_qty, fill_qty, price) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final PreparedStatement stmt;
    private final int batchSize;
    private int pending = 0;
    private long written = 0;

    public EventLogWriter(Connection conn, int batchSize) throws SQLException {
        this.stmt = conn.prepareStatement(INSERT_SQL);
        this.batchSize = batchSize;
    }

    public void append(OrderEvent e) throws SQLException {
        stmt.setLong(1, e.eventSeq);
        stmt.setLong(2, e.orderId);
        stmt.setString(3, e.eventType.name());
        stmt.setLong(4, e.eventTsUs);
        stmt.setString(5, e.symbol);
        stmt.setString(6, e.side.name());
        if (e.orderQty != null) stmt.setBigDecimal(7, e.orderQty); else stmt.setNull(7, Types.NUMERIC);
        if (e.fillQty != null) stmt.setBigDecimal(8, e.fillQty); else stmt.setNull(8, Types.NUMERIC);
        stmt.setBigDecimal(9, e.price);
        stmt.addBatch();
        pending++;
        written++;
        if (pending >= batchSize) flush();
    }

    public void flush() throws SQLException {
        if (pending == 0) return;
        stmt.executeBatch();
        pending = 0;
    }

    public long written() { return written; }

    @Override
    public void close() throws SQLException {
        flush();
        stmt.close();
    }
}
