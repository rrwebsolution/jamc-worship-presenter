package com.jamc.worship.config;

import java.nio.file.Path;

/**
 * Connection settings for the embedded SQLite database.
 *
 * @param databaseFile  location of the {@code worship.db} file
 * @param busyTimeoutMs how long a connection waits for a lock before failing
 */
public record DatabaseConfig(Path databaseFile, int busyTimeoutMs) {

    public static DatabaseConfig forPaths(AppPaths paths) {
        return new DatabaseConfig(paths.databaseFile(), 5_000);
    }

    public String jdbcUrl() {
        return "jdbc:sqlite:" + databaseFile.toAbsolutePath();
    }
}
