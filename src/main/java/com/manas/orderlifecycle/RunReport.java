package com.manas.orderlifecycle;

import com.manas.orderlifecycle.reconstruct.Reason;
import com.manas.orderlifecycle.rule605.CategoryStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Every figure one full pipeline run produced, collected in one place so
 * two independent runs (same seed, fresh databases) can be diffed field by
 * field -- this is what makes "every figure reproduced exactly on re-run"
 * a checked claim rather than an assumption. */
public final class RunReport {
    public long totalEventsWritten;
    public long lifecycleCount;
    public long correctionCount;
    public final Map<Reason, Long> correctionsByReason = new TreeMap<>();
    public List<CategoryStats> rule605 = new ArrayList<>();

    public List<String> diff(RunReport other) {
        List<String> diffs = new ArrayList<>();
        if (totalEventsWritten != other.totalEventsWritten)
            diffs.add("totalEventsWritten: " + totalEventsWritten + " vs " + other.totalEventsWritten);
        if (lifecycleCount != other.lifecycleCount)
            diffs.add("lifecycleCount: " + lifecycleCount + " vs " + other.lifecycleCount);
        if (correctionCount != other.correctionCount)
            diffs.add("correctionCount: " + correctionCount + " vs " + other.correctionCount);
        for (Reason r : Reason.values()) {
            long a = correctionsByReason.getOrDefault(r, 0L);
            long b = other.correctionsByReason.getOrDefault(r, 0L);
            if (a != b) diffs.add("correctionsByReason[" + r + "]: " + a + " vs " + b);
        }
        if (rule605.size() != other.rule605.size()) {
            diffs.add("rule605 category count: " + rule605.size() + " vs " + other.rule605.size());
        } else {
            for (int i = 0; i < rule605.size(); i++) {
                CategoryStats a = rule605.get(i);
                CategoryStats b = other.rule605.get(i);
                if (!a.sizeCategory.equals(b.sizeCategory) || a.orderCount != b.orderCount
                        || a.executedCount != b.executedCount
                        || Double.compare(a.avgTimeToExecMs, b.avgTimeToExecMs) != 0
                        || Double.compare(a.avgFillRatePct, b.avgFillRatePct) != 0) {
                    diffs.add("rule605[" + a.sizeCategory + "]: " + a + "  vs  " + b);
                }
            }
        }
        return diffs;
    }
}
