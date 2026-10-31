package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LowerThird;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite persistence for lower thirds. */
public class LowerThirdRepository {

    private static final String SELECT = """
            SELECT id, name, title, subtitle, font_family, font_size, title_color, font_color, background_color,
                   accent_color, logo_path, position, animation FROM lower_thirds""";

    private final DatabaseManager db;

    public LowerThirdRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<LowerThird> findAll() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SELECT + " ORDER BY name COLLATE NOCASE")) {
                List<LowerThird> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        });
    }

    public Optional<LowerThird> findById(long id) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public LowerThird save(LowerThird lt) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            if (lt.getId() == null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO lower_thirds(name, title, subtitle, font_family, font_size, title_color, font_color,
                                                 background_color, accent_color, logo_path, position, animation, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, lt);
                    ps.setString(13, now);
                    ps.setString(14, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE lower_thirds SET name = ?, title = ?, subtitle = ?, font_family = ?, font_size = ?, title_color = ?,
                                            font_color = ?, background_color = ?, accent_color = ?, logo_path = ?, position = ?,
                                            animation = ?, updated_at = ? WHERE id = ?""")) {
                bind(ps, lt);
                ps.setString(13, now);
                ps.setLong(14, lt.getId());
                ps.executeUpdate();
            }
            return lt.getId();
        });
        return findById(id).orElseThrow();
    }

    public void delete(long id) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM lower_thirds WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void bind(PreparedStatement ps, LowerThird lt) throws SQLException {
        ps.setString(1, lt.getName());
        ps.setString(2, lt.getTitle());
        ps.setString(3, lt.getSubtitle());
        ps.setString(4, lt.getFontFamily());
        ps.setInt(5, lt.getFontSize());
        ps.setString(6, lt.getTitleColor());
        ps.setString(7, lt.getSubtitleColor());
        ps.setString(8, lt.getBackgroundColor());
        ps.setString(9, lt.getAccentColor());
        ps.setString(10, lt.getLogoPath());
        ps.setString(11, lt.getPosition().name());
        ps.setString(12, lt.getAnimation().name());
    }

    private static LowerThird map(ResultSet rs) throws SQLException {
        LowerThird lt = new LowerThird();
        lt.setId(rs.getLong("id"));
        lt.setName(rs.getString("name"));
        lt.setTitle(rs.getString("title"));
        lt.setSubtitle(rs.getString("subtitle"));
        lt.setFontFamily(rs.getString("font_family") == null ? "Segoe UI" : rs.getString("font_family"));
        lt.setFontSize(rs.getInt("font_size"));
        lt.setTitleColor(rs.getString("title_color"));
        lt.setSubtitleColor(rs.getString("font_color") == null ? "#CBD5E1" : rs.getString("font_color"));
        lt.setBackgroundColor(rs.getString("background_color") == null ? "#0B1220" : rs.getString("background_color"));
        lt.setAccentColor(rs.getString("accent_color"));
        lt.setLogoPath(rs.getString("logo_path"));
        lt.setPosition(LowerThird.Position.parse(rs.getString("position")));
        lt.setAnimation(LowerThird.Animation.parse(rs.getString("animation")));
        return lt;
    }
}
