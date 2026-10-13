package com.manas.orderlifecycle;

import com.manas.orderlifecycle.db.Db;
import com.manas.orderlifecycle.db.EventLogWriter;
import com.manas.orderlifecycle.model.EventType;
import com.manas.orderlifecycle.model.OrderEvent;
import com.manas.orderlifecycle.model.Side;
import com.manas.orderlifecycle.model.SizeCategory;
import com.manas.orderlifecycle.reconstruct.GroupResult;
import com.manas.orderlifecycle.reconstruct.LifecycleReconstructor;
import com.manas.orderlifecycle.reconstruct.Reason;
import com.manas.orderlifecycle.reconstruct.ReconstructionRunner;
import com.manas.orderlifecycle.reconstruct.TerminalState;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled check suite (plain javac/java, no JUnit) over
 * {@link LifecycleReconstructor}'s pure function, {@link SizeCategory}'s
 * boundaries, and the append-only invariant, following this portfolio's own
 * precedent (trade-compliance-monitor) for Java repos with no build
 * framework.
 */
public final class Tests {
    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        testSizeCategoryBoundaries();
        testSimpleFill();
        testReplaceThenFill();
        testPartialFillsSumCorrectly();
        testCancelAfterPartialFill();
        testReject();
        testUnlinkableOrderId();
        testDuplicateNew();
        testNegativeOrZeroQty();
        testTimestampBeforeNew();
        testFillExceedsDeclaredQty();
        testMalformedEventDoesNotCorruptSiblingValidEvents();
        testAppendOnlyRowCountNeverChanges(args.length > 0 ? args[0] : "sql/schema.sql");

        System.out.printf("%d checks, %d failures%n", checks, failures);
        System.out.println(failures == 0 ? "PASS" : "FAIL");
        if (failures != 0) System.exit(1);
    }

    private static OrderEvent ev(long seq, long orderId, EventType type, long ts, BigDecimal orderQty,
                                  BigDecimal fillQty) {
        return new OrderEvent(seq, orderId, type, ts, "ALPA", Side.BUY, orderQty, fillQty, BigDecimal.valueOf(100, 2));
    }

    private static void testSizeCategoryBoundaries() {
        check(SizeCategory.classify(new BigDecimal("12.3456")) == SizeCategory.FRACTIONAL, "12.3456 is FRACTIONAL");
        check(SizeCategory.classify(new BigDecimal("1")) == SizeCategory.ODD_LOT, "1 is ODD_LOT");
        check(SizeCategory.classify(new BigDecimal("99")) == SizeCategory.ODD_LOT, "99 is ODD_LOT");
        check(SizeCategory.classify(new BigDecimal("100")) == SizeCategory.ROUND_LOT_SMALL, "100 is ROUND_LOT_SMALL");
        check(SizeCategory.classify(new BigDecimal("499")) == SizeCategory.ROUND_LOT_SMALL, "499 is ROUND_LOT_SMALL");
        check(SizeCategory.classify(new BigDecimal("500")) == SizeCategory.ROUND_LOT_MEDIUM, "500 is ROUND_LOT_MEDIUM");
        check(SizeCategory.classify(new BigDecimal("1999")) == SizeCategory.ROUND_LOT_MEDIUM, "1999 is ROUND_LOT_MEDIUM");
        check(SizeCategory.classify(new BigDecimal("2000")) == SizeCategory.ROUND_LOT_LARGE, "2000 is ROUND_LOT_LARGE");
        check(SizeCategory.classify(new BigDecimal("4999")) == SizeCategory.ROUND_LOT_LARGE, "4999 is ROUND_LOT_LARGE");
        check(SizeCategory.classify(new BigDecimal("5000")) == SizeCategory.BLOCK, "5000 is BLOCK");
        check(SizeCategory.classify(new BigDecimal("100.00")) == SizeCategory.ROUND_LOT_SMALL, "100.00 (trailing zeros) is whole");
    }

    private static void testSimpleFill() {
        List<OrderEvent> events = List.of(
            ev(1, 1, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 1, EventType.FILL, 2500, null, new BigDecimal("100"))
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle != null, "simple fill produces a lifecycle");
        check(r.lifecycle.terminalState == TerminalState.FILLED, "simple fill terminal state is FILLED");
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("100")) == 0, "simple fill total filled == 100");
        check(r.lifecycle.timeToExecMs == 1.5, "simple fill time-to-exec is (2500-1000)/1000 = 1.5ms");
        check(r.corrections.isEmpty(), "simple fill has no corrections");
    }

    private static void testReplaceThenFill() {
        List<OrderEvent> events = List.of(
            ev(1, 2, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 2, EventType.REPLACE, 1500, new BigDecimal("150"), null),
            ev(3, 2, EventType.FILL, 2000, null, new BigDecimal("150"))
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle.terminalState == TerminalState.FILLED, "replace-then-fill reaches FILLED");
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("150")) == 0, "replace-then-fill fills the replaced qty, 150");
        check(r.lifecycle.originalQty.compareTo(new BigDecimal("100")) == 0, "replace does not change original_qty (still 100)");
        check(r.lifecycle.sizeCategory == SizeCategory.ROUND_LOT_SMALL, "size_category is based on the original NEW qty (100), not the replaced one (150)");
    }

    private static void testPartialFillsSumCorrectly() {
        List<OrderEvent> events = List.of(
            ev(1, 3, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 3, EventType.PARTIAL_FILL, 1200, null, new BigDecimal("30")),
            ev(3, 3, EventType.PARTIAL_FILL, 1400, null, new BigDecimal("20")),
            ev(4, 3, EventType.FILL, 1600, null, new BigDecimal("50"))
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("100")) == 0, "three fills sum to the full 100");
        check(r.lifecycle.firstFillTsUs == 1200, "first_fill_ts is the FIRST partial fill, not the final FILL");
        check(r.lifecycle.timeToExecMs == 0.2, "time-to-exec measured to the first fill: (1200-1000)/1000 = 0.2ms");
    }

    private static void testCancelAfterPartialFill() {
        List<OrderEvent> events = List.of(
            ev(1, 4, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 4, EventType.PARTIAL_FILL, 1200, null, new BigDecimal("30")),
            ev(3, 4, EventType.CANCEL, 1400, null, null)
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle.terminalState == TerminalState.PARTIALLY_FILLED_CANCELED,
              "cancel after a partial fill is PARTIALLY_FILLED_CANCELED, not CANCELED");
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("30")) == 0, "partially-filled-then-canceled keeps the 30 filled");

        List<OrderEvent> noFillEvents = List.of(
            ev(1, 5, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 5, EventType.CANCEL, 1400, null, null)
        );
        GroupResult r2 = LifecycleReconstructor.reconstructGroup(noFillEvents);
        check(r2.lifecycle.terminalState == TerminalState.CANCELED, "cancel with no fill at all is plain CANCELED");
    }

    private static void testReject() {
        List<OrderEvent> events = List.of(
            ev(1, 6, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 6, EventType.REJECT, 1100, null, null)
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle.terminalState == TerminalState.REJECTED, "reject reaches REJECTED");
        check(r.lifecycle.timeToExecMs == null, "a rejected order has no time-to-exec");
    }

    private static void testUnlinkableOrderId() {
        List<OrderEvent> events = List.of(
            ev(1, 7, EventType.CANCEL, 1000, null, null)
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle == null, "an order_id with no NEW produces no lifecycle row");
        check(r.corrections.size() == 1 && r.corrections.get(0).reason == Reason.UNLINKABLE_ORDER_ID,
              "the lone Cancel is caught as UNLINKABLE_ORDER_ID");
    }

    private static void testDuplicateNew() {
        List<OrderEvent> events = List.of(
            ev(1, 8, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 8, EventType.NEW, 1200, new BigDecimal("999"), null),
            ev(3, 8, EventType.FILL, 1400, null, new BigDecimal("100"))
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle.originalQty.compareTo(new BigDecimal("100")) == 0,
              "the duplicate NEW's qty (999) never overwrites the real original_qty (100)");
        check(r.corrections.size() == 1 && r.corrections.get(0).reason == Reason.DUPLICATE_NEW,
              "the second NEW is caught as DUPLICATE_NEW");
        check(r.lifecycle.terminalState == TerminalState.FILLED, "the order still fills correctly despite the injected duplicate NEW");
    }

    private static void testNegativeOrZeroQty() {
        List<OrderEvent> events = List.of(
            ev(1, 9, EventType.NEW, 1000, BigDecimal.ZERO, null)
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.lifecycle == null, "a NEW with qty 0 produces no lifecycle row");
        check(r.corrections.get(0).reason == Reason.NEGATIVE_OR_ZERO_QTY, "zero qty caught as NEGATIVE_OR_ZERO_QTY");
    }

    private static void testTimestampBeforeNew() {
        List<OrderEvent> events = List.of(
            ev(1, 10, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 10, EventType.CANCEL, 500, null, null),  // before the NEW's own ts
            ev(3, 10, EventType.FILL, 2000, null, new BigDecimal("100"))
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.corrections.size() == 1 && r.corrections.get(0).reason == Reason.TIMESTAMP_BEFORE_NEW,
              "an event timestamped before its order's NEW is caught");
        check(r.lifecycle.terminalState == TerminalState.FILLED, "the real FILL still applies despite the out-of-order Cancel");
    }

    private static void testFillExceedsDeclaredQty() {
        List<OrderEvent> events = List.of(
            ev(1, 11, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 11, EventType.FILL, 1500, null, new BigDecimal("1000")), // 10x the declared qty
            ev(3, 11, EventType.FILL, 2000, null, new BigDecimal("100"))  // the real, correct fill
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.corrections.size() == 1 && r.corrections.get(0).reason == Reason.FILL_EXCEEDS_DECLARED_QTY,
              "the oversized fill is caught as FILL_EXCEEDS_DECLARED_QTY");
        check(r.lifecycle.terminalState == TerminalState.FILLED, "the order still reaches FILLED from its real fill");
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("100")) == 0,
              "total filled is 100, not 1100 -- the rejected fill was never applied");
    }

    private static void testMalformedEventDoesNotCorruptSiblingValidEvents() {
        // A malformed event sorted BETWEEN two real, valid events for the
        // same order must be skipped without disturbing the valid ones.
        List<OrderEvent> events = List.of(
            ev(1, 12, EventType.NEW, 1000, new BigDecimal("100"), null),
            ev(2, 12, EventType.PARTIAL_FILL, 1200, null, new BigDecimal("40")),
            ev(3, 12, EventType.FILL, 1300, null, new BigDecimal("1000")), // malformed, exceeds remaining 60
            ev(4, 12, EventType.FILL, 1400, null, new BigDecimal("60"))   // the real completion
        );
        GroupResult r = LifecycleReconstructor.reconstructGroup(events);
        check(r.corrections.size() == 1, "exactly one correction in a mixed valid/malformed group");
        check(r.lifecycle.totalFilledQty.compareTo(new BigDecimal("100")) == 0, "the two real fills still sum to 100");
        check(r.lifecycle.terminalState == TerminalState.FILLED, "the order still reaches FILLED");
    }

    private static void testAppendOnlyRowCountNeverChanges(String schemaSqlPath) throws Exception {
        try (Connection conn = Db.open("olr_test_append_only")) {
            Db.applySchema(conn, Path.of(schemaSqlPath));
            try (EventLogWriter writer = new EventLogWriter(conn, 100)) {
                writer.append(ev(1, 1, EventType.NEW, 1000, new BigDecimal("100"), null));
                writer.append(ev(2, 1, EventType.FILL, 2000, null, new BigDecimal("100")));
                writer.append(ev(3, 2, EventType.NEW, 1000, new BigDecimal("50"), null));
                writer.append(ev(4, 2, EventType.REJECT, 1100, null, null));
            }
            long before = countRows(conn, "order_events");
            ReconstructionRunner.run(conn, 100);
            long after = countRows(conn, "order_events");
            check(before == 4 && after == before, "order_events row count is unchanged by reconstruction (" + before + " -> " + after + ")");
        }
    }

    private static long countRows(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
