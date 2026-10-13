package com.manas.orderlifecycle.reconstruct;

import com.manas.orderlifecycle.model.EventType;
import com.manas.orderlifecycle.model.OrderEvent;
import com.manas.orderlifecycle.model.Side;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Streams order_events in (order_id, event_seq) order, groups consecutive
 * rows sharing an order_id, hands each group to
 * {@link LifecycleReconstructor#reconstructGroup}, and batches the results
 * into lifecycle_summary and correction_queue. A forward-only, read-only,
 * small-fetch-size ResultSet is used deliberately so 12M+ rows are never
 * materialized as Java objects all at once -- only one order's handful of
 * events are ever held in memory at a time.
 */
public final class ReconstructionRunner {

    public static final class Summary {
        public long lifecycleCount = 0;
        public long correctionCount = 0;
        public final Map<Reason, Long> correctionsByReason = new EnumMap<>(Reason.class);
    }

    private static final String SELECT_SQL =
        "SELECT event_seq, order_id, event_type, event_ts_us, symbol, side, order_qty, fill_qty, price " +
        "FROM order_events ORDER BY order_id, event_seq";

    private static final String INSERT_LIFECYCLE_SQL =
        "INSERT INTO lifecycle_summary (order_id, symbol, side, original_qty, size_category, new_ts_us, " +
        "terminal_state, total_filled_qty, first_fill_ts_us, time_to_exec_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_CORRECTION_SQL =
        "INSERT INTO correction_queue (event_seq, order_id, reason, queued_for) VALUES (?, ?, ?, 'T+3')";

    public static Summary run(Connection conn, int batchSize) throws SQLException {
        Summary summary = new Summary();
        for (Reason r : Reason.values()) summary.correctionsByReason.put(r, 0L);

        try (PreparedStatement lifecycleIns = conn.prepareStatement(INSERT_LIFECYCLE_SQL);
             PreparedStatement correctionIns = conn.prepareStatement(INSERT_CORRECTION_SQL);
             Statement select = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            select.setFetchSize(20_000);
            int lifecyclePending = 0;

            try (ResultSet rs = select.executeQuery(SELECT_SQL)) {
                List<OrderEvent> group = new ArrayList<>();
                long currentOrderId = Long.MIN_VALUE;
                boolean first = true;

                while (rs.next()) {
                    OrderEvent e = readEvent(rs);
                    if (!first && e.orderId != currentOrderId) {
                        lifecyclePending = flushGroup(group, lifecycleIns, correctionIns, summary, lifecyclePending, batchSize);
                        group.clear();
                    }
                    group.add(e);
                    currentOrderId = e.orderId;
                    first = false;
                }
                if (!group.isEmpty()) {
                    lifecyclePending = flushGroup(group, lifecycleIns, correctionIns, summary, lifecyclePending, batchSize);
                }
            }
            if (lifecyclePending > 0) lifecycleIns.executeBatch();
            correctionIns.executeBatch();
        }
        return summary;
    }

    private static int flushGroup(List<OrderEvent> group, PreparedStatement lifecycleIns, PreparedStatement correctionIns,
                                   Summary summary, int lifecyclePending, int batchSize) throws SQLException {
        GroupResult result = LifecycleReconstructor.reconstructGroup(group);

        if (result.lifecycle != null) {
            LifecycleResult l = result.lifecycle;
            lifecycleIns.setLong(1, l.orderId);
            lifecycleIns.setString(2, l.symbol);
            lifecycleIns.setString(3, l.side.name());
            lifecycleIns.setBigDecimal(4, l.originalQty);
            lifecycleIns.setString(5, l.sizeCategory.name());
            lifecycleIns.setLong(6, l.newTsUs);
            lifecycleIns.setString(7, l.terminalState.name());
            lifecycleIns.setBigDecimal(8, l.totalFilledQty);
            if (l.firstFillTsUs != null) lifecycleIns.setLong(9, l.firstFillTsUs);
            else lifecycleIns.setNull(9, java.sql.Types.BIGINT);
            if (l.timeToExecMs != null) lifecycleIns.setDouble(10, l.timeToExecMs);
            else lifecycleIns.setNull(10, java.sql.Types.DOUBLE);
            lifecycleIns.addBatch();
            lifecyclePending++;
            summary.lifecycleCount++;
            if (lifecyclePending >= batchSize) {
                lifecycleIns.executeBatch();
                lifecyclePending = 0;
            }
        }

        for (CorrectionEntry c : result.corrections) {
            correctionIns.setLong(1, c.eventSeq);
            correctionIns.setLong(2, c.orderId);
            correctionIns.setString(3, c.reason.name());
            correctionIns.addBatch();
            summary.correctionCount++;
            summary.correctionsByReason.merge(c.reason, 1L, Long::sum);
        }
        return lifecyclePending;
    }

    private static OrderEvent readEvent(ResultSet rs) throws SQLException {
        long eventSeq = rs.getLong("event_seq");
        long orderId = rs.getLong("order_id");
        EventType type = EventType.valueOf(rs.getString("event_type"));
        long ts = rs.getLong("event_ts_us");
        String symbol = rs.getString("symbol");
        Side side = Side.valueOf(rs.getString("side"));
        BigDecimal orderQty = rs.getBigDecimal("order_qty");
        BigDecimal fillQty = rs.getBigDecimal("fill_qty");
        BigDecimal price = rs.getBigDecimal("price");
        return new OrderEvent(eventSeq, orderId, type, ts, symbol, side, orderQty, fillQty, price);
    }
}
