package com.manas.orderlifecycle;

import com.manas.orderlifecycle.db.Db;
import com.manas.orderlifecycle.db.EventLogWriter;
import com.manas.orderlifecycle.generate.OrderEventGenerator;
import com.manas.orderlifecycle.generate.ReservoirEntry;
import com.manas.orderlifecycle.reconstruct.ReconstructionRunner;
import com.manas.orderlifecycle.rule605.Rule605Calculator;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * End to end: generate -> append to the order_events log -> reconstruct
 * lifecycles and the correction queue -> compute Rule 605 stats -> print.
 * Then does the whole thing a second time, from the same seed into a fresh
 * database, and diffs every reported figure against the first run.
 *
 * Usage: Main <numOrders> <countPerReason> <schemaSqlPath> [batchSize]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        long numOrders = args.length > 0 ? Long.parseLong(args[0]) : 3_100_000L;
        long countPerReason = args.length > 1 ? Long.parseLong(args[1]) : 3_680L;
        Path schemaSqlPath = Path.of(args.length > 2 ? args[2] : "sql/schema.sql");
        int batchSize = args.length > 3 ? Integer.parseInt(args[3]) : 20_000;
        long seed = 0x5EED5EEDL;

        System.out.printf("run 1: numOrders=%d countPerReason=%d%n", numOrders, countPerReason);
        long t0 = System.nanoTime();
        RunReport report1 = runOnce("olr_run1", seed, numOrders, countPerReason, schemaSqlPath, batchSize);
        printReport("run 1", report1, (System.nanoTime() - t0) / 1_000_000);

        System.out.println();
        System.out.printf("run 2: numOrders=%d countPerReason=%d (same seed, fresh database)%n", numOrders, countPerReason);
        t0 = System.nanoTime();
        RunReport report2 = runOnce("olr_run2", seed, numOrders, countPerReason, schemaSqlPath, batchSize);
        printReport("run 2", report2, (System.nanoTime() - t0) / 1_000_000);

        List<String> diffs = report1.diff(report2);
        System.out.println();
        if (diffs.isEmpty()) {
            System.out.println("REPRODUCIBILITY: PASS -- every figure reproduced exactly on re-run.");
        } else {
            System.out.println("REPRODUCIBILITY: FAIL -- differences found:");
            for (String d : diffs) System.out.println("  " + d);
            System.exit(1);
        }
    }

    private static RunReport runOnce(String dbName, long seed, long numOrders, long countPerReason,
                                      Path schemaSqlPath, int batchSize) throws Exception {
        try (Connection conn = Db.open(dbName)) {
            conn.setAutoCommit(true);
            Db.applySchema(conn, schemaSqlPath);

            OrderEventGenerator gen = new OrderEventGenerator(seed, 1);
            long reservoirStride = Math.max(1, numOrders / 50_000);
            List<ReservoirEntry> reservoir;
            try (EventLogWriter writer = new EventLogWriter(conn, batchSize)) {
                reservoir = gen.generateValid(numOrders, reservoirStride, e -> {
                    try { writer.append(e); } catch (SQLException ex) { throw new RuntimeException(ex); }
                });
                long unlinkableIdStart = numOrders + 1;
                long zeroQtyIdStart = unlinkableIdStart + countPerReason + 1000;
                gen.generateMalformed(countPerReason, unlinkableIdStart, zeroQtyIdStart, reservoir, e -> {
                    try { writer.append(e); } catch (SQLException ex) { throw new RuntimeException(ex); }
                });
            }

            RunReport report = new RunReport();
            report.totalEventsWritten = gen.nextEventSeq() - 1;

            ReconstructionRunner.Summary summary = ReconstructionRunner.run(conn, batchSize);
            report.lifecycleCount = summary.lifecycleCount;
            report.correctionCount = summary.correctionCount;
            report.correctionsByReason.putAll(summary.correctionsByReason);

            report.rule605 = Rule605Calculator.compute(conn);
            return report;
        }
    }

    private static void printReport(String label, RunReport r, long wallMs) {
        System.out.printf("%s: %,d events written, %,d lifecycles, %,d corrections (%,d ms)%n",
                label, r.totalEventsWritten, r.lifecycleCount, r.correctionCount, wallMs);
        r.correctionsByReason.forEach((reason, count) -> System.out.printf("  %-28s %,d%n", reason, count));
        System.out.println("  Rule 605-style stats by size category:");
        for (var stats : r.rule605) System.out.println("    " + stats);
    }
}
