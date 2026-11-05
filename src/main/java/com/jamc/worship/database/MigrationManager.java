package com.jamc.worship.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Versioned, forward-only schema migrations.
 *
 * <p>Each migration runs in its own transaction and is recorded in {@code schema_version}.
 * Before upgrading a database that already holds data, a consistent snapshot is written
 * to {@code backups/} so a failed upgrade can never lose a church's library.
 */
public final class MigrationManager {

    private static final Logger log = LoggerFactory.getLogger(MigrationManager.class);

    private final DatabaseManager db;

    public MigrationManager(DatabaseManager db) {
        this.db = db;
    }

    record Migration(int version, String description, DatabaseManager.SqlFunction<Connection, Void> apply) {
    }

    static List<Migration> migrations() {
        return List.of(
                new Migration(1, "Initial schema", conn -> {
                    executeScript(conn, Schema.V1);
                    return null;
                }),
                new Migration(2, "Default themes and sample song", conn -> {
                    SeedData.apply(conn);
                    return null;
                }),
                new Migration(3, "Theme typography and slide decks", conn -> {
                    executeScript(conn, Schema.V3);
                    SeedData.applyV3(conn);
                    return null;
                }),
                new Migration(4, "Media, Bible, scenes and lower thirds", conn -> {
                    executeScript(conn, Schema.V4);
                    SeedData.applyV4(conn);
                    return null;
                }),
                new Migration(5, "Bible versions and providers", conn -> {
                    executeScript(conn, Schema.V5);
                    return null;
                }),
                new Migration(6, "PowerPoint presentation library", conn -> {
                    executeScript(conn, Schema.V6);
                    return null;
                }),
                new Migration(7, "Fit or fill for background images and videos", conn -> {
                    executeScript(conn, Schema.V7);
                    return null;
                }),
                new Migration(8, "Song section order", conn -> {
                    executeScript(conn, Schema.V8);
                    return null;
                }),
                new Migration(9, "Global video background behavior per theme", conn -> {
                    executeScript(conn, Schema.V9);
                    return null;
                }),
                new Migration(10, "Typography: font weight, text case, shadow strength and per-song overrides", conn -> {
                    executeScript(conn, Schema.V10);
                    return null;
                }),
                new Migration(11, "OBS-style default scenes (Camera + Lyrics, readability overlay)", conn -> {
                    executeScript(conn, Schema.V11);
                    SeedData.applyV11(conn);
                    return null;
                }),
                new Migration(12, "Presentations from the library as scene sources (Sermon, Camera + Presentation)", conn -> {
                    SeedData.applyV12(conn);
                    return null;
                }),
                new Migration(13, "YouTube videos in the video library; YouTube references and sync times for lyrics", conn -> {
                    executeScript(conn, Schema.V13);
                    return null;
                }),
                new Migration(14, "Lyrics source and provider (search, paste, file import, YouTube reference)", conn -> {
                    executeScript(conn, Schema.V14);
                    return null;
                }),
                new Migration(15, "Tags on service items (Fast, Slow, ...)", conn -> {
                    executeScript(conn, Schema.V15);
                    return null;
                }),
                new Migration(16, "NASB as a standard Bible version", conn -> {
                    executeScript(conn, Schema.V16);
                    return null;
                })
        );
    }

    public static int latestVersion() {
        return migrations().getLast().version();
    }

    public void migrate() {
        db.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version     INTEGER PRIMARY KEY,
                            description TEXT NOT NULL,
                            applied_at  TEXT NOT NULL
                        )""");
            }
            return null;
        });

        int current = currentVersion();
        List<Migration> pending = migrations().stream().filter(m -> m.version() > current).toList();
        if (pending.isEmpty()) {
            log.info("Database schema is up to date (version {})", current);
            return;
        }
        if (current > 0) {
            snapshotBeforeUpgrade(current);
        }
        for (Migration m : pending) {
            log.info("Applying migration {}: {}", m.version(), m.description());
            db.inTransaction(conn -> {
                m.apply().apply(conn);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_version(version, description, applied_at) VALUES (?, ?, ?)")) {
                    ps.setInt(1, m.version());
                    ps.setString(2, m.description());
                    ps.setString(3, Instant.now().toString());
                    ps.executeUpdate();
                }
                return null;
            });
        }
        log.info("Database migrated from version {} to {}", current, latestVersion());
    }

    public int currentVersion() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private void snapshotBeforeUpgrade(int fromVersion) {
        Path dbFile = db.config().databaseFile();
        Path backupsDir = dbFile.getParent().resolveSibling("backups");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = backupsDir.resolve("pre-migration-v" + fromVersion + "-" + stamp + ".db");
        try {
            java.nio.file.Files.createDirectories(backupsDir);
            db.query(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
                    ps.setString(1, target.toString());
                    ps.execute();
                }
                return null;
            });
            log.info("Pre-migration snapshot written to {}", target);
        } catch (Exception e) {
            // A failed snapshot must not block startup; the migration itself is still transactional.
            log.warn("Could not write pre-migration snapshot to {}", target, e);
        }
    }

    /** Executes semicolon-separated DDL. Scripts must not contain triggers or semicolons inside literals. */
    static void executeScript(Connection conn, String script) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql.strip());
                }
            }
        }
    }
}
