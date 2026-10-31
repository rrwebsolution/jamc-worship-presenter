package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

/**
 * Key/value settings stored in the database. Used for library-level settings (such as the default
 * theme) that must travel with the database in backups; window and UI preferences stay in
 * {@code config/settings.properties}.
 */
public class SettingsRepository {

    public static final String DEFAULT_THEME_ID = "presentation.default_theme_id";

    private final DatabaseManager db;

    public SettingsRepository(DatabaseManager db) {
        this.db = db;
    }

    public Optional<String> get(String key) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public Optional<Long> getLong(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.strip()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    public void set(String key, String value) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO settings(key, value, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
