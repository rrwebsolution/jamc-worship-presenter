package com.jamc.worship.repository;

import com.jamc.worship.bible.BibleVersion;
import com.jamc.worship.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite storage for the list of Bible versions and how each one gets its text. */
public class BibleVersionRepository {

    private final DatabaseManager db;

    public BibleVersionRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<BibleVersion> findAll() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT id, abbreviation, name, language, provider, local_file, enabled, provider_config, sort_order "
                            + "FROM bible_versions ORDER BY sort_order, abbreviation")) {
                List<BibleVersion> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        });
    }

    public Optional<BibleVersion> find(String abbreviation) {
        return findAll().stream().filter(v -> v.abbreviation().equalsIgnoreCase(abbreviation)).findFirst();
    }

    /** Inserts a new version or updates the one with the same abbreviation. */
    public BibleVersion save(BibleVersion v) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO bible_versions(abbreviation, name, language, provider, local_file, enabled, provider_config, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(abbreviation) DO UPDATE SET name = excluded.name, language = excluded.language,
                        provider = excluded.provider, local_file = excluded.local_file, enabled = excluded.enabled,
                        provider_config = excluded.provider_config""")) {
                ps.setString(1, v.abbreviation().strip());
                ps.setString(2, v.name());
                ps.setString(3, v.language());
                ps.setString(4, v.provider().name());
                ps.setString(5, v.localFile());
                ps.setInt(6, v.enabled() ? 1 : 0);
                ps.setString(7, v.providerConfig());
                ps.setInt(8, v.sortOrder());
                ps.executeUpdate();
            }
            return null;
        });
        return find(v.abbreviation()).orElseThrow();
    }

    public void delete(long id) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bible_versions WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static BibleVersion map(ResultSet rs) throws SQLException {
        return new BibleVersion(rs.getLong("id"), rs.getString("abbreviation"), rs.getString("name"), rs.getString("language"),
                BibleVersion.Provider.parse(rs.getString("provider")), rs.getString("local_file"), rs.getInt("enabled") != 0,
                rs.getString("provider_config"), rs.getInt("sort_order"));
    }
}
