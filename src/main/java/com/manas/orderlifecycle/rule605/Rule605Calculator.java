package com.manas.orderlifecycle.rule605;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule 605-style execution-quality statistics by order-size category,
 * computed with one real SQL aggregation against lifecycle_summary rather
 * than in Java: this is the one place in the project where "SQL" (as
 * opposed to "a database accessed via SQL") is actually doing the work.
 */
public final class Rule605Calculator {
    private static final String SQL =
        "SELECT size_category, " +
        "       COUNT(*) AS order_count, " +
        "       COUNT(time_to_exec_ms) AS executed_count, " +
        "       AVG(time_to_exec_ms) AS avg_time_to_exec_ms, " +
        "       AVG(CASE WHEN time_to_exec_ms IS NOT NULL THEN (total_filled_qty / original_qty) * 100 END) AS avg_fill_rate_pct " +
        "FROM lifecycle_summary " +
        "GROUP BY size_category " +
        "ORDER BY size_category";

    public static List<CategoryStats> compute(Connection conn) throws SQLException {
        List<CategoryStats> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL)) {
            while (rs.next()) {
                String category = rs.getString("size_category");
                long orderCount = rs.getLong("order_count");
                long executedCount = rs.getLong("executed_count");
                double avgTime = rs.getDouble("avg_time_to_exec_ms");
                double avgFillRate = rs.getDouble("avg_fill_rate_pct");
                out.add(new CategoryStats(category, orderCount, executedCount, avgTime, avgFillRate));
            }
        }
        return out;
    }
}
