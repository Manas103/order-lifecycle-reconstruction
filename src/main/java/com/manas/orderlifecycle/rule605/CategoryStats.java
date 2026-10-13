package com.manas.orderlifecycle.rule605;

public final class CategoryStats {
    public final String sizeCategory;
    public final long orderCount;        // all lifecycles in this category, executed or not
    public final long executedCount;     // lifecycles with at least one fill
    public final double avgTimeToExecMs; // NaN if executedCount == 0
    public final double avgFillRatePct;  // mean (total_filled_qty / original_qty) * 100 over executed orders

    public CategoryStats(String sizeCategory, long orderCount, long executedCount,
                          double avgTimeToExecMs, double avgFillRatePct) {
        this.sizeCategory = sizeCategory;
        this.orderCount = orderCount;
        this.executedCount = executedCount;
        this.avgTimeToExecMs = avgTimeToExecMs;
        this.avgFillRatePct = avgFillRatePct;
    }

    @Override
    public String toString() {
        return String.format("%-16s orders=%-9d executed=%-9d avg_time_to_exec_ms=%-10s avg_fill_rate_pct=%s",
                sizeCategory, orderCount, executedCount,
                executedCount > 0 ? String.format("%.4f", avgTimeToExecMs) : "n/a",
                executedCount > 0 ? String.format("%.2f", avgFillRatePct) : "n/a");
    }
}
