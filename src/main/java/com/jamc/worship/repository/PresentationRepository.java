package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Presentation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite storage for the presentation library (the rendered slides are files, not rows). */
public class PresentationRepository {

    private static final String COLUMNS = "id, name, source_path, folder, slide_count, renderer, source_size, "
            + "source_modified, warning_count, imported_at, updated_at";

    private final DatabaseManager db;

    public PresentationRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<Presentation> findAll(String search) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip() + "%";
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM presentations"
                    + (like == null ? "" : " WHERE name LIKE ? OR source_path LIKE ?")
                    + " ORDER BY name COLLATE NOCASE")) {
                if (like != null) {
                    ps.setString(1, like);
                    ps.setString(2, like);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Presentation> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public Optional<Presentation> find(long id) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM presentations WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public Presentation insert(Presentation p) {
        String now = LocalDateTime.now().toString();
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO presentations(name, source_path, folder, slide_count, renderer, source_size,
                        source_modified, warning_count, imported_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, p);
                ps.setString(9, now);
                ps.setString(10, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return new Presentation(keys.getLong(1), p.name(), p.sourcePath(), p.folder(), p.slideCount(),
                            p.renderer(), p.sourceSize(), p.sourceModified(), p.warningCount(), now, now);
                }
            }
        });
    }

    /** Replaces everything but the import date (after a rename or a refresh). */
    public void update(Presentation p) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE presentations SET name = ?, source_path = ?, folder = ?, slide_count = ?, renderer = ?,
                        source_size = ?, source_modified = ?, warning_count = ?, updated_at = ?
                    WHERE id = ?""")) {
                bind(ps, p);
                ps.setString(9, LocalDateTime.now().toString());
                ps.setLong(10, p.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void delete(long id) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM presentations WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void bind(PreparedStatement ps, Presentation p) throws SQLException {
        ps.setString(1, p.name());
        ps.setString(2, p.sourcePath());
        ps.setString(3, p.folder());
        ps.setInt(4, p.slideCount());
        ps.setString(5, p.renderer());
        ps.setLong(6, p.sourceSize());
        ps.setLong(7, p.sourceModified());
        ps.setInt(8, p.warningCount());
    }

    private static Presentation map(ResultSet rs) throws SQLException {
        return new Presentation(rs.getLong("id"), rs.getString("name"), rs.getString("source_path"),
                rs.getString("folder"), rs.getInt("slide_count"), rs.getString("renderer"), rs.getLong("source_size"),
                rs.getLong("source_modified"), rs.getInt("warning_count"), rs.getString("imported_at"),
                rs.getString("updated_at"));
    }
}
