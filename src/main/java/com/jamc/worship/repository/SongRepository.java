package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LyricsSync;
import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.model.SongSection;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.util.Json;

import java.sql.Connection;
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

/** SQLite persistence for songs, their sections, and their favorite flag. */
public class SongRepository {

    static final String FAVORITE_ITEM_TYPE = "SONG";

    private static final String SELECT_COLUMNS = """
            SELECT s.id, s.title, s.author, s.copyright, s.ccli_number, s.tags, s.theme_id, s.background,
                   s.font_family, s.font_size, s.arrangement, s.style_json, s.created_at, s.updated_at,
                   s.youtube_video_id, s.youtube_url, s.youtube_media_id, s.sync_json,
                   s.source_type, s.source_provider, s.source_reference,
                   EXISTS (SELECT 1 FROM favorites f WHERE f.item_type = 'SONG' AND f.item_id = s.id) AS favorite
            FROM songs s
            """;

    private final DatabaseManager db;

    /** Saves only the section order (null = as written). Kept apart from song saves so editing lyrics keeps it. */
    public void setArrangement(long songId, String arrangement) {
        db.inTransaction(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE songs SET arrangement = ? WHERE id = ?")) {
                ps.setString(1, arrangement);
                ps.setLong(2, songId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Saves only the song's font, size and other typography overrides (null = follow the theme and
     * global lyrics typography), so the quick typography bar never touches the lyrics.
     */
    public void setTypography(long songId, String fontFamily, Integer fontSize, StyleOverrides typography) {
        String json = typography == null || typography.isEmpty() ? null : Json.write(typography);
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE songs SET font_family = ?, font_size = ?, style_json = ? WHERE id = ?")) {
                ps.setString(1, fontFamily == null || fontFamily.isBlank() ? null : fontFamily);
                if (fontSize == null) {
                    ps.setNull(2, Types.INTEGER);
                } else {
                    ps.setInt(2, fontSize);
                }
                ps.setString(3, json);
                ps.setLong(4, songId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Saves only the song's YouTube reference (video, link, linked Video Library item, sync times); null
     * values clear it. Kept apart from song saves so editing the lyrics never drops it.
     */
    public void setYouTubeReference(long songId, String videoId, String url, Long mediaId, LyricsSync sync) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE songs SET youtube_video_id = ?, youtube_url = ?, youtube_media_id = ?, sync_json = ? WHERE id = ?")) {
                bindYouTube(ps, 1, videoId, url, mediaId, sync);
                ps.setLong(5, songId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes every song's own font, size and typography, so all songs follow the global lyrics typography. */
    public int clearAllTypography() {
        return db.inTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate("UPDATE songs SET font_family = NULL, font_size = NULL, style_json = NULL "
                        + "WHERE font_family IS NOT NULL OR font_size IS NOT NULL OR style_json IS NOT NULL");
            }
        });
    }

    /** Saves only the sync times (Sync Lyrics). */
    public void setSync(long songId, LyricsSync sync) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE songs SET sync_json = ? WHERE id = ?")) {
                ps.setString(1, sync == null || sync.isEmpty() ? null : Json.write(sync));
                ps.setLong(2, songId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void bindYouTube(PreparedStatement ps, int first, String videoId, String url, Long mediaId, LyricsSync sync)
            throws SQLException {
        ps.setString(first, blankToNull(videoId));
        ps.setString(first + 1, blankToNull(url));
        if (mediaId == null) {
            ps.setNull(first + 2, Types.INTEGER);
        } else {
            ps.setLong(first + 2, mediaId);
        }
        ps.setString(first + 3, sync == null || sync.isEmpty() ? null : Json.write(sync));
    }

    public SongRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Returns song summaries (no sections) matching the query. Every search word must match somewhere. */
    public List<Song> findAll(SongQuery query) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(" WHERE 1 = 1");
        List<String> params = new ArrayList<>();
        String text = query.text() == null ? "" : query.text().strip();
        if (!text.isEmpty()) {
            for (String term : text.split("\\s+")) {
                String like = "%" + escapeLike(term) + "%";
                sql.append(' ').append("""
                        AND (s.title LIKE ? ESCAPE '\\' OR s.author LIKE ? ESCAPE '\\' OR s.tags LIKE ? ESCAPE '\\'
                              OR EXISTS (SELECT 1 FROM song_sections ss
                                         WHERE ss.song_id = s.id AND ss.content LIKE ? ESCAPE '\\'))""");
                params.add(like);
                params.add(like);
                params.add(like);
                params.add(like);
            }
        }
        if (query.favoritesOnly()) {
            sql.append(" AND EXISTS (SELECT 1 FROM favorites f WHERE f.item_type = 'SONG' AND f.item_id = s.id)");
        }
        sql.append(" ORDER BY ").append(orderBy(query.sort()));

        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                List<Song> songs = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Song song = mapSong(rs);
                        song.setSectionsLoaded(false);
                        songs.add(song);
                    }
                }
                return songs;
            }
        });
    }

    /** Loads a song with all of its sections. */
    public Optional<Song> findById(long id) {
        return db.query(conn -> {
            Song song;
            try (PreparedStatement ps = conn.prepareStatement(SELECT_COLUMNS + " WHERE s.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    song = mapSong(rs);
                }
            }
            song.setSections(loadSections(conn, id));
            song.setSectionsLoaded(true);
            return Optional.of(song);
        });
    }

    public int count() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM songs")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Inserts a new song with its sections and returns the stored copy. */
    public Song insert(Song song) {
        Instant now = Instant.now();
        long id = db.inTransaction(conn -> {
            long newId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO songs(title, author, copyright, ccli_number, tags, theme_id, background,
                                      font_family, font_size, created_at, updated_at, style_json,
                                      youtube_video_id, youtube_url, youtube_media_id, sync_json,
                                      source_type, source_provider, source_reference)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                bindSongColumns(ps, song);
                ps.setString(10, now.toString());
                ps.setString(11, now.toString());
                ps.setString(12, song.getTypography() == null ? null : Json.write(song.getTypography()));
                bindYouTube(ps, 13, song.getYoutubeVideoId(), song.getYoutubeUrl(), song.getYoutubeMediaId(), song.getSync());
                ps.setString(17, song.getSourceType() == null ? null : song.getSourceType().name());
                ps.setString(18, blankToNull(song.getSourceProvider()));
                ps.setString(19, blankToNull(song.getSourceReference()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No id generated for new song");
                    }
                    newId = keys.getLong(1);
                }
            }
            insertSections(conn, newId, song.getSections());
            writeFavorite(conn, newId, song.isFavorite());
            return newId;
        });
        return findById(id).orElseThrow();
    }

    /** Replaces an existing song and all of its sections. */
    public Song update(Song song) {
        if (song.getId() == null) {
            throw new IllegalArgumentException("Song has no id; use insert()");
        }
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE songs SET title = ?, author = ?, copyright = ?, ccli_number = ?, tags = ?, theme_id = ?,
                                     background = ?, font_family = ?, font_size = ?, updated_at = ?
                    WHERE id = ?""")) {
                bindSongColumns(ps, song);
                ps.setString(10, Instant.now().toString());
                ps.setLong(11, song.getId());
                if (ps.executeUpdate() == 0) {
                    throw new SQLException("Song " + song.getId() + " no longer exists");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM song_sections WHERE song_id = ?")) {
                ps.setLong(1, song.getId());
                ps.executeUpdate();
            }
            insertSections(conn, song.getId(), song.getSections());
            writeFavorite(conn, song.getId(), song.isFavorite());
            return null;
        });
        return findById(song.getId()).orElseThrow();
    }

    /** Deletes a song, its sections (via cascade) and its favorite entry. Returns false if it did not exist. */
    public boolean delete(long id) {
        return db.inTransaction(conn -> {
            writeFavorite(conn, id, false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM songs WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public void setFavorite(long id, boolean favorite) {
        db.inTransaction(conn -> {
            writeFavorite(conn, id, favorite);
            return null;
        });
    }

    /** Returns true if any song other than {@code excludeId} already uses this title (case-insensitive). */
    public boolean titleExists(String title, Long excludeId) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM songs WHERE title = ? COLLATE NOCASE AND id <> ? LIMIT 1")) {
                ps.setString(1, title.strip());
                ps.setLong(2, excludeId == null ? -1 : excludeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private static void bindSongColumns(PreparedStatement ps, Song song) throws SQLException {
        ps.setString(1, song.getTitle().strip());
        ps.setString(2, blankToNull(song.getAuthor()));
        ps.setString(3, blankToNull(song.getCopyright()));
        ps.setString(4, blankToNull(song.getCcliNumber()));
        ps.setString(5, blankToNull(song.getTags()));
        if (song.getThemeId() == null) {
            ps.setNull(6, Types.INTEGER);
        } else {
            ps.setLong(6, song.getThemeId());
        }
        ps.setString(7, blankToNull(song.getBackground()));
        ps.setString(8, blankToNull(song.getFontFamily()));
        if (song.getFontSize() == null) {
            ps.setNull(9, Types.INTEGER);
        } else {
            ps.setInt(9, song.getFontSize());
        }
    }

    private static void insertSections(Connection conn, long songId, List<SongSection> sections) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO song_sections(song_id, section_type, label, content, sort_order) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < sections.size(); i++) {
                SongSection section = sections.get(i);
                ps.setLong(1, songId);
                ps.setString(2, section.getType().name());
                ps.setString(3, section.getLabel() == null || section.getLabel().isBlank()
                        ? section.getType().displayName() : section.getLabel().strip());
                ps.setString(4, section.getContent() == null ? "" : section.getContent());
                ps.setInt(5, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static List<SongSection> loadSections(Connection conn, long songId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, section_type, label, content FROM song_sections WHERE song_id = ? ORDER BY sort_order, id")) {
            ps.setLong(1, songId);
            List<SongSection> sections = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SongSection section = new SongSection(
                            SectionType.fromDatabase(rs.getString("section_type")),
                            rs.getString("label"),
                            rs.getString("content"));
                    section.setId(rs.getLong("id"));
                    sections.add(section);
                }
            }
            return sections;
        }
    }

    private static void writeFavorite(Connection conn, long songId, boolean favorite) throws SQLException {
        String sql = favorite
                ? "INSERT OR IGNORE INTO favorites(item_type, item_id, created_at) VALUES (?, ?, ?)"
                : "DELETE FROM favorites WHERE item_type = ? AND item_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, FAVORITE_ITEM_TYPE);
            ps.setLong(2, songId);
            if (favorite) {
                ps.setString(3, Instant.now().toString());
            }
            ps.executeUpdate();
        }
    }

    private static Song mapSong(ResultSet rs) throws SQLException {
        Song song = new Song();
        song.setId(rs.getLong("id"));
        song.setTitle(rs.getString("title"));
        song.setAuthor(rs.getString("author"));
        song.setCopyright(rs.getString("copyright"));
        song.setCcliNumber(rs.getString("ccli_number"));
        song.setTags(rs.getString("tags"));
        long themeId = rs.getLong("theme_id");
        song.setThemeId(rs.wasNull() ? null : themeId);
        song.setBackground(rs.getString("background"));
        song.setFontFamily(rs.getString("font_family"));
        int fontSize = rs.getInt("font_size");
        song.setFontSize(rs.wasNull() ? null : fontSize);
        song.setArrangement(rs.getString("arrangement"));
        String styleJson = rs.getString("style_json");
        song.setTypography(styleJson == null || styleJson.isBlank() ? null : Json.readOrNull(styleJson, StyleOverrides.class));
        song.setCreatedAt(parseInstant(rs.getString("created_at")));
        song.setUpdatedAt(parseInstant(rs.getString("updated_at")));
        song.setFavorite(rs.getBoolean("favorite"));
        song.setYoutubeVideoId(rs.getString("youtube_video_id"));
        song.setYoutubeUrl(rs.getString("youtube_url"));
        long mediaId = rs.getLong("youtube_media_id");
        song.setYoutubeMediaId(rs.wasNull() ? null : mediaId);
        String sync = rs.getString("sync_json");
        song.setSync(sync == null || sync.isBlank() ? null : Json.readOrNull(sync, LyricsSync.class));
        song.setSourceType(com.jamc.worship.model.LyricsSourceType.parse(rs.getString("source_type")));
        song.setSourceProvider(rs.getString("source_provider"));
        song.setSourceReference(rs.getString("source_reference"));
        return song;
    }

    private static String orderBy(SongQuery.Sort sort) {
        return switch (sort == null ? SongQuery.Sort.TITLE_ASC : sort) {
            case TITLE_ASC -> "s.title COLLATE NOCASE ASC, s.id ASC";
            case TITLE_DESC -> "s.title COLLATE NOCASE DESC, s.id DESC";
            case AUTHOR -> "(s.author IS NULL OR s.author = ''), s.author COLLATE NOCASE ASC, s.title COLLATE NOCASE ASC";
            case RECENTLY_UPDATED -> "s.updated_at DESC, s.id DESC";
            case RECENTLY_ADDED -> "s.created_at DESC, s.id DESC";
        };
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
