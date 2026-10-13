package com.manas.orderlifecycle.reconstruct;

import com.manas.orderlifecycle.model.OrderEvent;
import com.manas.orderlifecycle.model.SizeCategory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs one order_id's lifecycle from its events, which arrive here
 * already sorted by event_seq (the caller groups consecutive rows of a
 * {@code SELECT ... ORDER BY order_id, event_seq} scan).
 *
 * This is a pure function on purpose: {@link #reconstructGroup} takes a
 * list and returns a result, nothing more, which is what makes it directly
 * unit-testable against hand-built event lists (see the test suite) without
 * any database involved, and what makes the streaming driver
 * ({@code Main}) a thin loop around it.
 */
public final class LifecycleReconstructor {
    private LifecycleReconstructor() {}

    public static GroupResult reconstructGroup(List<OrderEvent> events) {
        boolean hasNew = false;
        long newTs = 0;
        BigDecimal declaredQty = null;
        BigDecimal originalQty = null;
        String symbol = null;
        com.manas.orderlifecycle.model.Side side = null;
        BigDecimal totalFilled = BigDecimal.ZERO;
        Long firstFillTs = null;
        TerminalState terminal = null;
        List<CorrectionEntry> corrections = new ArrayList<>();

        for (OrderEvent e : events) {
            if (e.eventType == com.manas.orderlifecycle.model.EventType.NEW) {
                if (hasNew) {
                    corrections.add(new CorrectionEntry(e.eventSeq, e.orderId, Reason.DUPLICATE_NEW));
                    continue;
                }
                if (e.orderQty == null || e.orderQty.signum() <= 0) {
                    corrections.add(new CorrectionEntry(e.eventSeq, e.orderId, Reason.NEGATIVE_OR_ZERO_QTY));
                    continue;
                }
                hasNew = true;
                newTs = e.eventTsUs;
                declaredQty = e.orderQty;
                originalQty = e.orderQty;
                symbol = e.symbol;
                side = e.side;
                continue;
            }

            if (!hasNew) {
                corrections.add(new CorrectionEntry(e.eventSeq, e.orderId, Reason.UNLINKABLE_ORDER_ID));
                continue;
            }
            if (e.eventTsUs < newTs) {
                corrections.add(new CorrectionEntry(e.eventSeq, e.orderId, Reason.TIMESTAMP_BEFORE_NEW));
                continue;
            }

            switch (e.eventType) {
                case REPLACE:
                    if (e.orderQty != null) declaredQty = e.orderQty;
                    break;
                case PARTIAL_FILL:
                case FILL: {
                    BigDecimal after = totalFilled.add(e.fillQty);
                    if (after.compareTo(declaredQty) > 0) {
                        corrections.add(new CorrectionEntry(e.eventSeq, e.orderId, Reason.FILL_EXCEEDS_DECLARED_QTY));
                        break;
                    }
                    totalFilled = after;
                    if (firstFillTs == null) firstFillTs = e.eventTsUs;
                    if (e.eventType == com.manas.orderlifecycle.model.EventType.FILL) terminal = TerminalState.FILLED;
                    break;
                }
                case CANCEL:
                    terminal = totalFilled.signum() > 0 ? TerminalState.PARTIALLY_FILLED_CANCELED : TerminalState.CANCELED;
                    break;
                case REJECT:
                    terminal = TerminalState.REJECTED;
                    break;
                default:
                    break;
            }
        }

        LifecycleResult lifecycle = null;
        if (hasNew) {
            TerminalState finalState = terminal != null ? terminal : TerminalState.OPEN;
            Double timeToExecMs = firstFillTs != null ? (firstFillTs - newTs) / 1000.0 : null;
            lifecycle = new LifecycleResult(events.get(0).orderId, symbol, side, originalQty,
                    SizeCategory.classify(originalQty), newTs, finalState, totalFilled, firstFillTs, timeToExecMs);
        }
        return new GroupResult(lifecycle, corrections);
    }
}
