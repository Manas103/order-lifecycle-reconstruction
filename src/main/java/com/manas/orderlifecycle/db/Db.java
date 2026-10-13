package com.manas.orderlifecycle.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Connects to an in-memory H2 database in PostgreSQL-compatibility mode,
 * standing in for a real PostgreSQL instance the way the portfolio's other
 * Java repos already do (see securities-settlement-matching,
 * strategy-parameter-rollout-gate): no standalone PostgreSQL server is
 * reachable in this build environment, and {@code MODE=PostgreSQL} makes H2
 * reject the same things real PostgreSQL would (e.g. implicit numeric
 * truncation) rather than silently being more permissive.
 */
public final class Db {
    private Db() {}

    public static Connection open(String dbName) throws SQLException {
        // Deliberately no DB_CLOSE_DELAY=-1: this project opens exactly one
        // connection per run and wants the in-memory database (12M+ rows
        // across three tables) dropped the moment that connection closes,
        // so a second run does not have to hold both runs' data in heap at
        // once. securities-settlement-matching's H2 setup uses
        // DB_CLOSE_DELAY=-1 instead, deliberately, because its test context
        // opens many short-lived connections against one logical database
        // and needs it to outlive any single one of them; this project's
        // one-connection-per-run shape has the opposite requirement.
        String url = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL";
        return DriverManager.getConnection(url, "sa", "");
    }

    public static void applySchema(Connection conn, Path schemaSqlPath) throws SQLException, IOException {
        String script = Files.readString(schemaSqlPath);
        try (Statement st = conn.createStatement()) {
            for (String stmt : script.split(";")) {
                String trimmed = stripComments(stmt).trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
        }
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}
