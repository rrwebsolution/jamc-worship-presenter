package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.BackgroundMode;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.Theme;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite persistence for themes. */
public class ThemeRepository {

    private static final String SELECT = """
            SELECT id, name, is_builtin, background_type, background_value, background_color, overlay_opacity,
                   font_family, font_size, font_weight, italic, font_color, text_align, text_position,
                   line_spacing, shadow, outline, outline_color, outline_width, background_fit, background_mode,
                   text_transform, shadow_strength, typography_override
            FROM themes
            """;

    private final DatabaseManager db;

    public ThemeRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<Theme> findAll() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(SELECT + " ORDER BY is_builtin DESC, name COLLATE NOCASE")) {
                List<Theme> themes = new ArrayList<>();
                while (rs.next()) {
                    themes.add(map(rs));
                }
                return themes;
            }
        });
    }

    public Optional<Theme> findById(long id) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public boolean nameExists(String name, Long excludeId) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM themes WHERE name = ? COLLATE NOCASE AND id <> ? LIMIT 1")) {
                ps.setString(1, name.strip());
                ps.setLong(2, excludeId == null ? -1 : excludeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public Theme insert(Theme theme) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO themes(name, background_type, background_value, background_color, overlay_opacity,
                                       font_family, font_size, font_weight, italic, font_color, text_align,
                                       text_position, line_spacing, shadow, outline, outline_color, outline_width,
                                       background_fit, background_mode, text_transform, shadow_strength, typography_override,
                                       is_builtin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""",
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, theme);
                ps.setString(23, now);
                ps.setString(24, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
        return findById(id).orElseThrow();
    }

    public Theme update(Theme theme) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE themes SET name = ?, background_type = ?, background_value = ?, background_color = ?,
                                      overlay_opacity = ?, font_family = ?, font_size = ?, font_weight = ?, italic = ?,
                                      font_color = ?, text_align = ?, text_position = ?, line_spacing = ?, shadow = ?,
                                      outline = ?, outline_color = ?, outline_width = ?, background_fit = ?, background_mode = ?,
                                      text_transform = ?, shadow_strength = ?, typography_override = ?, updated_at = ?
                    WHERE id = ?""")) {
                bind(ps, theme);
                ps.setString(23, Instant.now().toString());
                ps.setLong(24, theme.getId());
                if (ps.executeUpdate() == 0) {
                    throw new SQLException("Theme " + theme.getId() + " no longer exists");
                }
            }
            return null;
        });
        return findById(theme.getId()).orElseThrow();
    }

    public boolean delete(long id) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM themes WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Number of songs and slide decks that use this theme. */
    public int usageCount(long id) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT (SELECT COUNT(*) FROM songs WHERE theme_id = ?) + (SELECT COUNT(*) FROM slide_decks WHERE theme_id = ?)""")) {
                ps.setLong(1, id);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static void bind(PreparedStatement ps, Theme theme) throws SQLException {
        SlideStyle s = theme.getStyle();
        ps.setString(1, theme.getName().strip());
        ps.setString(2, s.backgroundType().name());
        ps.setString(3, s.backgroundValue());
        ps.setString(4, s.backgroundColor());
        ps.setDouble(5, s.overlayOpacity());
        ps.setString(6, s.fontFamily());
        ps.setInt(7, s.fontSize());
        ps.setString(8, s.fontWeight().name());
        ps.setInt(9, s.italic() ? 1 : 0);
        ps.setString(10, s.fontColor());
        ps.setString(11, s.align().name());
        ps.setString(12, s.position().name());
        ps.setDouble(13, s.lineSpacing());
        ps.setInt(14, s.shadow() ? 1 : 0);
        ps.setInt(15, s.outline() ? 1 : 0);
        ps.setString(16, s.outlineColor());
        ps.setInt(17, s.outlineWidth());
        ps.setString(18, s.backgroundFit().name());
        ps.setString(19, s.backgroundMode().name());
        ps.setString(20, s.textTransform().name());
        ps.setDouble(21, s.shadowStrength());
        ps.setInt(22, theme.isTypographyOverride() ? 1 : 0);
    }

    private static FitMode parseFit(String v) {
        try {
            return v == null ? FitMode.FILL : FitMode.valueOf(v);
        } catch (IllegalArgumentException e) {
            return FitMode.FILL;
        }
    }

    private static Theme map(ResultSet rs) throws SQLException {
        SlideStyle style = new SlideStyle(
                BackgroundType.parse(rs.getString("background_type"), BackgroundType.COLOR),
                rs.getString("background_value"),
                rs.getString("background_color"),
                rs.getDouble("overlay_opacity"),
                rs.getString("font_family"),
                rs.getInt("font_size"),
                FontWeight.parse(rs.getString("font_weight"), FontWeight.BOLD),
                rs.getInt("italic") != 0,
                rs.getString("font_color"),
                TextAlign.parse(rs.getString("text_align"), TextAlign.CENTER),
                TextPosition.parse(rs.getString("text_position"), TextPosition.MIDDLE),
                rs.getDouble("line_spacing"),
                rs.getInt("shadow") != 0,
                rs.getInt("outline") != 0,
                rs.getString("outline_color"),
                rs.getInt("outline_width"),
                parseFit(rs.getString("background_fit")),
                BackgroundMode.parse(rs.getString("background_mode")),
                TextTransform.parse(rs.getString("text_transform"), TextTransform.ORIGINAL),
                rs.getDouble("shadow_strength"));
        Theme theme = new Theme(rs.getLong("id"), rs.getString("name"), rs.getInt("is_builtin") != 0, style);
        theme.setTypographyOverride(rs.getInt("typography_override") != 0);
        return theme;
    }
}
