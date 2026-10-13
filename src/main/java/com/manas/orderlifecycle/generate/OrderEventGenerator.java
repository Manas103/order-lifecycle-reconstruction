package com.manas.orderlifecycle.generate;

import com.manas.orderlifecycle.model.EventType;
import com.manas.orderlifecycle.model.OrderEvent;
import com.manas.orderlifecycle.model.Side;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Deterministic, self-consistent order-event generation.
 *
 * {@link Random} is used deliberately rather than a hand-written PRNG: its
 * algorithm (a 48-bit linear congruential generator) and {@code nextInt}'s
 * rejection-sampling formula are specified exactly in the JDK's own
 * documentation, so a fixed seed reproduces the identical draw sequence on
 * any conforming JVM. The sibling C++ repos in this portfolio write their
 * own splitmix64 for exactly this reason, because the C++ standard leaves
 * {@code std::uniform_int_distribution}'s algorithm implementation-defined;
 * Java does not have that problem, so reimplementing a PRNG here would be
 * solving a problem that does not exist on this platform.
 *
 * Quantities are generated and accumulated as integer "qty units" (1 unit =
 * 0.0001 shares) and only converted to {@link BigDecimal} at the point an
 * event is emitted, so partial-fill arithmetic never depends on
 * floating-point or BigDecimal rounding mode -- the same reason the sibling
 * C++ decoder keeps prices as integer ticks rather than floats.
 */
public final class OrderEventGenerator {

    public static final long UNITS_PER_SHARE = 10_000;
    private static final String[] SYMBOLS = {
        "ALPA", "BRVX", "CNTL", "DRFT", "ECHO", "FLUX", "GRVT", "HLIX",
        "IONX", "JOLT", "KRNL", "LUMN", "MVRK", "NXUS", "OPTR", "PRSM",
        "QRTZ", "RVLN", "SOLR", "TNDM"
    };

    private final Random rng;
    private long eventSeq;
    private long clockUs = 1_700_000_000_000_000L; // arbitrary fixed epoch, microseconds

    public OrderEventGenerator(long seed, long startingEventSeq) {
        this.rng = new Random(seed);
        this.eventSeq = startingEventSeq;
    }

    public long nextEventSeq() {
        return eventSeq;
    }

    private long advanceClock(long minUs, long maxUs) {
        clockUs += minUs + rng.nextInt((int) (maxUs - minUs + 1));
        return clockUs;
    }

    private BigDecimal unitsToQty(long units) {
        return BigDecimal.valueOf(units, 4);
    }

    /** Picks a category's unit range by the fixed weights used throughout
     * this project's measured run, then draws a quantity inside it. */
    private long drawQtyUnits() {
        int roll = rng.nextInt(100);
        if (roll < 5) {
            // FRACTIONAL: 0.0001 to 50.0000 shares, forced non-whole.
            long units = 1 + rng.nextInt(500_000);
            if (units % UNITS_PER_SHARE == 0) units += 1;
            return units;
        } else if (roll < 20) {
            return (1 + rng.nextInt(99)) * UNITS_PER_SHARE;              // ODD_LOT 1-99
        } else if (roll < 60) {
            return (100 + rng.nextInt(400)) * UNITS_PER_SHARE;           // SMALL 100-499
        } else if (roll < 85) {
            return (500 + rng.nextInt(1500)) * UNITS_PER_SHARE;          // MEDIUM 500-1999
        } else if (roll < 95) {
            return (2000 + rng.nextInt(3000)) * UNITS_PER_SHARE;         // LARGE 2000-4999
        } else {
            return (5000 + rng.nextInt(15001)) * UNITS_PER_SHARE;        // BLOCK 5000-20000
        }
    }

    /**
     * Generates {@code numOrders} self-consistent order lifecycles (each
     * one's events are a valid NEW -> [REPLACE] -> [PARTIAL_FILL]* ->
     * (FILL | CANCEL | REJECT) sequence) and hands every event to
     * {@code sink} in emission order. Every {@code reservoirStride}-th
     * order is additionally recorded into the returned reservoir, for the
     * malformed-event generator to build realistic defects against.
     */
    public List<ReservoirEntry> generateValid(long numOrders, long reservoirStride, Consumer<OrderEvent> sink) {
        List<ReservoirEntry> reservoir = new ArrayList<>();
        for (long orderId = 1; orderId <= numOrders; orderId++) {
            String symbol = SYMBOLS[(int) (orderId % SYMBOLS.length)];
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qtyUnits = drawQtyUnits();
            BigDecimal price = BigDecimal.valueOf(1000 + rng.nextInt(49000), 2); // $10.00-$500.00

            long newTs = advanceClock(50, 2_000_000); // 0.05ms to 2s since the previous event
            sink.accept(new OrderEvent(eventSeq++, orderId, EventType.NEW, newTs, symbol, side,
                    unitsToQty(qtyUnits), null, price));

            if (orderId % reservoirStride == 0) {
                reservoir.add(new ReservoirEntry(orderId, newTs, unitsToQty(qtyUnits)));
            }

            int outcomeRoll = rng.nextInt(100);
            if (outcomeRoll < 5) {
                // REJECT: the only thing that can follow NEW.
                long ts = advanceClock(50, 500_000);
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.REJECT, ts, symbol, side,
                        null, null, price));
                continue;
            }

            long declaredUnits = qtyUnits;
            if (rng.nextInt(100) < 30) {
                double factor = 0.8 + rng.nextDouble() * 0.4; // 0.8x - 1.2x
                declaredUnits = Math.max(1, Math.round(qtyUnits * factor));
                BigDecimal newPrice = price.add(BigDecimal.valueOf(rng.nextInt(21) - 10, 2));
                long ts = advanceClock(50, 1_000_000);
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.REPLACE, ts, symbol, side,
                        unitsToQty(declaredUnits), null, newPrice));
                price = newPrice;
            }

            if (outcomeRoll < 15) {
                // CANCEL with no fill at all.
                long ts = advanceClock(50, 1_000_000);
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.CANCEL, ts, symbol, side,
                        null, null, price));
                continue;
            }

            // EXECUTE: 0-4 partial fills, each taking 10%-60% of what remains,
            // then a final FILL for whatever is left (always > 0).
            long remainingUnits = declaredUnits;
            int numPartials = rng.nextInt(5);
            for (int p = 0; p < numPartials && remainingUnits > 10; p++) {
                long maxTake = (remainingUnits * 60) / 100;
                long minTake = Math.max(1, (remainingUnits * 10) / 100);
                if (maxTake <= minTake) break;
                long take = minTake + rng.nextInt((int) (maxTake - minTake + 1));
                remainingUnits -= take;
                long ts = advanceClock(50, 3_000_000);
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.PARTIAL_FILL, ts, symbol, side,
                        null, unitsToQty(take), price));
            }
            boolean cancelRemainder = outcomeRoll >= 85; // the ~15% of executors that part-fill then cancel
            long ts = advanceClock(50, 3_000_000);
            if (cancelRemainder) {
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.CANCEL, ts, symbol, side,
                        null, null, price));
            } else {
                sink.accept(new OrderEvent(eventSeq++, orderId, EventType.FILL, ts, symbol, side,
                        null, unitsToQty(remainingUnits), price));
            }
        }
        return reservoir;
    }

    /**
     * Generates exactly {@code countPerReason} malformed events for each of
     * the five reasons below (five reasons, evenly split, always caught by
     * construction -- see README "Findings" for why each one is guaranteed
     * to trip its check regardless of where in the log it lands):
     *
     *   UNLINKABLE_ORDER_ID        references an order_id that never had a NEW
     *   DUPLICATE_NEW              a second NEW for an order_id already live
     *   NEGATIVE_OR_ZERO_QTY       a NEW with qty <= 0
     *   TIMESTAMP_BEFORE_NEW       an event timestamped before its order's own NEW
     *   FILL_EXCEEDS_DECLARED_QTY  a fill far larger than the order ever declared
     */
    public void generateMalformed(long countPerReason, long unlinkableIdStart, long zeroQtyIdStart,
                                   List<ReservoirEntry> reservoir, Consumer<OrderEvent> sink) {
        String symbol = SYMBOLS[0];

        for (long i = 0; i < countPerReason; i++) {
            long fakeOrderId = unlinkableIdStart + i;
            long ts = advanceClock(50, 1_000_000);
            sink.accept(new OrderEvent(eventSeq++, fakeOrderId, EventType.CANCEL, ts, symbol, Side.BUY,
                    null, null, BigDecimal.valueOf(100, 2)));
        }

        for (long i = 0; i < countPerReason; i++) {
            ReservoirEntry e = reservoir.get((int) (i % reservoir.size()));
            long ts = advanceClock(50, 1_000_000);
            sink.accept(new OrderEvent(eventSeq++, e.orderId, EventType.NEW, ts, symbol, Side.BUY,
                    e.qty, null, BigDecimal.valueOf(100, 2)));
        }

        for (long i = 0; i < countPerReason; i++) {
            long fakeOrderId = zeroQtyIdStart + i;
            long ts = advanceClock(50, 1_000_000);
            BigDecimal badQty = (i % 2 == 0) ? BigDecimal.ZERO : BigDecimal.valueOf(-100, 4);
            sink.accept(new OrderEvent(eventSeq++, fakeOrderId, EventType.NEW, ts, symbol, Side.SELL,
                    badQty, null, BigDecimal.valueOf(100, 2)));
        }

        for (long i = 0; i < countPerReason; i++) {
            ReservoirEntry e = reservoir.get((int) (i % reservoir.size()));
            long ts = e.newTsUs - 1_000 - i; // strictly before that order's own NEW
            sink.accept(new OrderEvent(eventSeq++, e.orderId, EventType.CANCEL, ts, symbol, Side.BUY,
                    null, null, BigDecimal.valueOf(100, 2)));
        }

        for (long i = 0; i < countPerReason; i++) {
            ReservoirEntry e = reservoir.get((int) (i % reservoir.size()));
            BigDecimal hugeFill = e.qty.multiply(BigDecimal.TEN); // 10x the order's own declared qty
            long ts = advanceClock(50, 1_000_000);
            sink.accept(new OrderEvent(eventSeq++, e.orderId, EventType.FILL, ts, symbol, Side.BUY,
                    null, hugeFill, BigDecimal.valueOf(100, 2)));
        }
    }
}
