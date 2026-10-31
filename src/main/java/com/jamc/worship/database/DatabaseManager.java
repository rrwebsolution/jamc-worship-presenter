package com.jamc.worship.database;

import com.jamc.worship.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hands out SQLite connections with consistent pragmas.
 *
 * <p>SQLite connections are cheap to open, so each unit of work opens its own
 * connection. This keeps background workers independent of each other; WAL mode
 * lets readers proceed while a writer is active.
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final DatabaseConfig config;
    private final SQLiteConfig sqliteConfig;

    public DatabaseManager(DatabaseConfig config) {
        this.config = config;
        this.sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setBusyTimeout(config.busyTimeoutMs());
    }

    public DatabaseConfig config() {
        return config;
    }

    /** Creates the database file (if needed) and applies pending migrations. */
    public void initialize() {
        try {
            Files.createDirectories(config.databaseFile().getParent());
        } catch (IOException e) {
            throw new DatabaseException("Could not create database directory", e);
        }
        boolean existed = Files.exists(config.databaseFile());
        new MigrationManager(this).migrate();
        log.info("Database ready at {} ({})", config.databaseFile(), existed ? "existing" : "created");
    }

    public Connection getConnection() throws SQLException {
        return sqliteConfig.createConnection(config.jdbcUrl());
    }

    /** Runs {@code work} inside a transaction, committing on success and rolling back on any failure. */
    public <T> T inTransaction(SqlFunction<Connection, T> work) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Database transaction failed: " + e.getMessage(), e);
        }
    }

    /** Runs read-only {@code work} on a fresh connection. */
    public <T> T query(SqlFunction<Connection, T> work) {
        try (Connection conn = getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw new DatabaseException("Database query failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }
}
