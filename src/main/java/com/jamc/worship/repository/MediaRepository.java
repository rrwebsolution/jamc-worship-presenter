package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.MediaSourceType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite persistence for the media library. Favorites live in the shared favorites table. */
public class MediaRepository {

    private static final String SELECT = """
            SELECT m.id, m.name, m.media_type, m.category, m.file_path, m.is_copied, m.file_size, m.duration_ms,
                   m.width, m.height, m.created_at, m.source_type, m.youtube_id, m.channel_name, m.thumbnail_url,
                   EXISTS (SELECT 1 FROM favorites f WHERE f.item_type = 'MEDIA' AND f.item_id = m.id) AS favorite
            FROM media m
            """;

    private final DatabaseManager db;

    public MediaRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Items in the given categories (all if empty) whose name matches {@code text}. */
    public List<MediaItem> find(List<MediaCategory> categories, String text, boolean favoritesOnly) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!categories.isEmpty()) {
            sql.append(" AND m.category IN (").append("?,".repeat(categories.size() - 1)).append("?)");
            categories.forEach(c -> params.add(c.name()));
        }
        if (text != null && !text.isBlank()) {
            sql.append(" AND m.name LIKE ? ESCAPE '\\'");
            params.add("%" + SongRepository.escapeLike(text.strip()) + "%");
        }
        if (favoritesOnly) {
            sql.append(" AND EXISTS (SELECT 1 FROM favorites f WHERE f.item_type = 'MEDIA' AND f.item_id = m.id)");
        }
        sql.append(" ORDER BY m.name COLLATE NOCASE");
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                List<MediaItem> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
                return out;
            }
        });
    }

    public Optional<MediaItem> findById(long id) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE m.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    /** The library item for this YouTube video, if it was already added. */
    public Optional<MediaItem> findByYouTubeId(String videoId) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE m.youtube_id = ? AND m.source_type = 'YOUTUBE' LIMIT 1")) {
                ps.setString(1, videoId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public MediaItem insert(MediaItem m) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO media(name, media_type, category, file_path, is_copied, file_size, duration_ms, width, height,
                                      created_at, updated_at, source_type, youtube_id, channel_name, thumbnail_url)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, m.getName());
                ps.setString(2, m.getKind().name());
                ps.setString(3, m.getCategory().name());
                ps.setString(4, m.getPath());
                ps.setInt(5, m.isCopied() ? 1 : 0);
                setLong(ps, 6, m.getFileSize());
                setLong(ps, 7, m.getDurationMs());
                setLong(ps, 8, m.getWidth() == null ? null : m.getWidth().longValue());
                setLong(ps, 9, m.getHeight() == null ? null : m.getHeight().longValue());
                ps.setString(10, now);
                ps.setString(11, now);
                ps.setString(12, m.getSourceType().name());
                ps.setString(13, m.getYoutubeVideoId());
                ps.setString(14, m.getChannelName());
                ps.setString(15, m.getThumbnailUrl());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
        return findById(id).orElseThrow();
    }

    public void rename(long id, String name) {
        update("UPDATE media SET name = ?, updated_at = ? WHERE id = ?", name, Instant.now().toString(), id);
    }

    public void setCategory(long id, MediaCategory category) {
        update("UPDATE media SET category = ?, updated_at = ? WHERE id = ?", category.name(), Instant.now().toString(), id);
    }

    public void setPath(long id, String path) {
        update("UPDATE media SET file_path = ?, updated_at = ? WHERE id = ?", path, Instant.now().toString(), id);
    }

    /** The library item of an online video (YouTube or Pixabay) with this provider id, if it was added. */
    public Optional<MediaItem> findBySource(MediaSourceType source, String providerId) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE m.youtube_id = ? AND m.source_type = ? LIMIT 1")) {
                ps.setString(1, providerId);
                ps.setString(2, source.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public void setDuration(long id, long durationMs) {
        update("UPDATE media SET duration_ms = ? WHERE id = ?", durationMs, id);
    }

    public boolean delete(long id) {
        return db.inTransaction(conn -> {
            try (PreparedStatement f = conn.prepareStatement("DELETE FROM favorites WHERE item_type = 'MEDIA' AND item_id = ?")) {
                f.setLong(1, id);
                f.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM media WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public void setFavorite(long id, boolean favorite) {
        db.inTransaction(conn -> {
            String sql = favorite
                    ? "INSERT OR IGNORE INTO favorites(item_type, item_id, created_at) VALUES ('MEDIA', ?, ?)"
                    : "DELETE FROM favorites WHERE item_type = 'MEDIA' AND item_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                if (favorite) {
                    ps.setString(2, Instant.now().toString());
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Number of library items stored at this path (used before deleting a copied file). */
    public int countByPath(String path) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM media WHERE file_path = ?")) {
                ps.setString(1, path);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private void update(String sql, Object... params) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }

    private static MediaItem map(ResultSet rs) throws SQLException {
        MediaItem m = new MediaItem();
        m.setId(rs.getLong("id"));
        m.setName(rs.getString("name"));
        m.setKind(MediaKind.parse(rs.getString("media_type"), MediaKind.IMAGE));
        m.setCategory(MediaCategory.parse(rs.getString("category"), MediaCategory.forKind(m.getKind())));
        m.setPath(rs.getString("file_path"));
        m.setCopied(rs.getInt("is_copied") != 0);
        long size = rs.getLong("file_size");
        m.setFileSize(rs.wasNull() ? null : size);
        long dur = rs.getLong("duration_ms");
        m.setDurationMs(rs.wasNull() ? null : dur);
        int w = rs.getInt("width");
        m.setWidth(rs.wasNull() ? null : w);
        int h = rs.getInt("height");
        m.setHeight(rs.wasNull() ? null : h);
        m.setFavorite(rs.getBoolean("favorite"));
        m.setSourceType(MediaSourceType.parse(rs.getString("source_type")));
        m.setYoutubeVideoId(rs.getString("youtube_id"));
        m.setChannelName(rs.getString("channel_name"));
        m.setThumbnailUrl(rs.getString("thumbnail_url"));
        try {
            String c = rs.getString("created_at");
            m.setCreatedAt(c == null ? null : Instant.parse(c));
        } catch (DateTimeParseException e) {
            m.setCreatedAt(null);
        }
        return m;
    }
}
