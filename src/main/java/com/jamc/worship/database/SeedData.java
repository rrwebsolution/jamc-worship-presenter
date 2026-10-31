package com.jamc.worship.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** First-run content: built-in themes and two public-domain hymns so the library is not empty. */
final class SeedData {

    private SeedData() {
    }

    static void apply(Connection conn) throws SQLException {
        insertThemes(conn);
        insertSong(conn, "Amazing Grace", "John Newton", "Public Domain", "hymn, grace, classic",
                new String[][]{
                        {"VERSE", "Verse 1", """
                                Amazing grace! How sweet the sound
                                That saved a wretch like me!
                                I once was lost, but now am found;
                                Was blind, but now I see."""},
                        {"VERSE", "Verse 2", """
                                'Twas grace that taught my heart to fear,
                                And grace my fears relieved;
                                How precious did that grace appear
                                The hour I first believed."""},
                        {"VERSE", "Verse 3", """
                                Through many dangers, toils and snares,
                                I have already come;
                                'Tis grace hath brought me safe thus far,
                                And grace will lead me home."""},
                        {"VERSE", "Verse 4", """
                                When we've been there ten thousand years,
                                Bright shining as the sun,
                                We've no less days to sing God's praise
                                Than when we'd first begun."""}
                });
        insertSong(conn, "Doxology", "Thomas Ken", "Public Domain", "hymn, praise, closing",
                new String[][]{
                        {"VERSE", "Verse 1", """
                                Praise God, from whom all blessings flow;
                                Praise Him, all creatures here below;
                                Praise Him above, ye heavenly host;
                                Praise Father, Son, and Holy Ghost."""},
                        {"ENDING", "Ending", "Amen."}
                });
    }

    /** Tunes the built-in themes for the new typography columns, sets the default theme, adds a sample deck. */
    static void applyV3(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE themes SET line_spacing = 1.15, outline_width = 4 WHERE is_builtin = 1");
            st.executeUpdate("UPDATE themes SET line_spacing = 1.25 WHERE name = 'Bible' AND is_builtin = 1");
            st.executeUpdate("UPDATE themes SET outline = 1, outline_width = 3, overlay_opacity = 0.35 "
                    + "WHERE name = 'Worship Dark' AND is_builtin = 1");
            st.executeUpdate("UPDATE themes SET italic = 1 WHERE name = 'Bible' AND is_builtin = 1");
        }

        Long defaultThemeId = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM themes WHERE name = 'Worship Dark'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                defaultThemeId = rs.getLong(1);
            }
        }
        if (defaultThemeId != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO settings(key, value, updated_at) VALUES ('presentation.default_theme_id', ?, ?)")) {
                ps.setString(1, String.valueOf(defaultThemeId));
                ps.setString(2, now);
                ps.executeUpdate();
            }
        }

        Long announcementTheme = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM themes WHERE name = 'Announcement'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                announcementTheme = rs.getLong(1);
            }
        }
        long deckId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO slide_decks(name, theme_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "Welcome & Announcements");
            ps.setObject(2, announcementTheme);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                deckId = keys.getLong(1);
            }
        }
        String[][] slides = {
                {"Welcome", "Welcome to Worship", "We're glad you're here", null},
                {"Announcement", "Midweek Prayer Meeting\nWednesday · 7:00 PM", "All are welcome",
                        "{\"position\":\"BOTTOM\",\"fontSize\":64}"},
                {"Closing", "Go in peace", null, "{\"backgroundType\":\"COLOR\",\"backgroundValue\":\"#0B1220\"}"},
        };
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO slides(owner_type, owner_id, sort_order, label, content, caption, style_json, created_at, updated_at)
                VALUES ('DECK', ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (int i = 0; i < slides.length; i++) {
                ps.setLong(1, deckId);
                ps.setInt(2, i);
                ps.setString(3, slides[i][0]);
                ps.setString(4, slides[i][1]);
                ps.setString(5, slides[i][2]);
                ps.setString(6, slides[i][3]);
                ps.setString(7, now);
                ps.setString(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Default scenes and their sources (listed top layer first), plus a sample lower third.
     * Cameras start hidden because most computers used for slides have no camera pointed at the stage.
     */
    static void applyV4(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        String full = "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FILL\"}";
        String logo = "{\"x\":0.86,\"y\":0.05,\"w\":0.1,\"h\":0.12,\"fit\":\"FIT\"}";
        String pip = "{\"x\":0.66,\"y\":0.62,\"w\":0.3,\"h\":0.3,\"fit\":\"FILL\"}";
        String clock = "{\"x\":0.78,\"y\":0.04,\"w\":0.18,\"h\":0.08,\"color\":\"#FFFFFF\",\"fontSize\":48}";
        Object[][] scenes = {
                {"Worship", new String[][]{{"Lower Third", "LOWER_THIRD", "1", full}, {"Church Logo", "CHURCH_LOGO", "1", logo},
                        {"Lyrics", "LYRICS", "1", full}, {"Camera", "CAMERA", "0", full}, {"Background", "BACKGROUND", "1", full}}},
                {"Bible", new String[][]{{"Lower Third", "LOWER_THIRD", "1", full}, {"Bible Text", "BIBLE", "1", full},
                        {"Background", "BACKGROUND", "1", full}}},
                {"Sermon", new String[][]{{"Lower Third", "LOWER_THIRD", "1", full}, {"Sermon Points", "LYRICS", "1", full},
                        {"Camera", "CAMERA", "0", pip}, {"Background", "BACKGROUND", "1", full}}},
                {"Camera Only", new String[][]{{"Lower Third", "LOWER_THIRD", "1", full}, {"Camera", "CAMERA", "1", full}}},
                {"Announcement", new String[][]{{"Clock", "CLOCK", "0", clock}, {"Church Logo", "CHURCH_LOGO", "1", logo},
                        {"Text", "LYRICS", "1", full}, {"Background", "BACKGROUND", "1", full}}},
                {"Video", new String[][]{{"Video", "BACKGROUND", "1", full}}},
                {"Blank", new String[][]{}},
                {"Logo Screen", new String[][]{{"Church Logo", "CHURCH_LOGO", "1",
                        "{\"x\":0.3,\"y\":0.25,\"w\":0.4,\"h\":0.5,\"fit\":\"FIT\"}"}}},
        };
        try (PreparedStatement sceneStmt = conn.prepareStatement(
                "INSERT INTO scenes(name, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
             PreparedStatement sourceStmt = conn.prepareStatement(
                     "INSERT INTO sources(scene_id, name, source_type, visible, sort_order, config_json) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < scenes.length; i++) {
                sceneStmt.setString(1, (String) scenes[i][0]);
                sceneStmt.setInt(2, i);
                sceneStmt.setString(3, now);
                sceneStmt.setString(4, now);
                sceneStmt.executeUpdate();
                long sceneId;
                try (ResultSet keys = sceneStmt.getGeneratedKeys()) {
                    keys.next();
                    sceneId = keys.getLong(1);
                }
                String[][] sources = (String[][]) scenes[i][1];
                for (int s = 0; s < sources.length; s++) {
                    sourceStmt.setLong(1, sceneId);
                    sourceStmt.setString(2, sources[s][0]);
                    sourceStmt.setString(3, sources[s][1]);
                    sourceStmt.setInt(4, Integer.parseInt(sources[s][2]));
                    sourceStmt.setInt(5, s);
                    sourceStmt.setString(6, sources[s][3]);
                    sourceStmt.addBatch();
                }
                sourceStmt.executeBatch();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO lower_thirds(name, title, subtitle, font_family, font_color, background_color, position,
                                         animation, title_color, accent_color, font_size, created_at, updated_at)
                VALUES ('Pastor', 'Ptr. Carlo', 'Sunday Service', 'Segoe UI', '#CBD5E1', '#0B1220', 'BOTTOM_LEFT',
                        'SLIDE', '#FFFFFF', '#1677FF', 52, ?, ?)""")) {
            ps.setString(1, now);
            ps.setString(2, now);
            ps.executeUpdate();
        }
    }

    private static void insertThemes(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        // name, bg type, bg value, font, size, weight, color, align, position, shadow, outline, overlay
        Object[][] themes = {
                {"Worship Dark", "COLOR", "#0B1220", "Segoe UI", 72, "BOLD", "#FFFFFF", "CENTER", "MIDDLE", 1, 0, 0.0},
                {"Worship Light", "COLOR", "#F8FAFC", "Segoe UI", 72, "BOLD", "#0F172A", "CENTER", "MIDDLE", 0, 0, 0.0},
                {"Bible", "COLOR", "#111827", "Georgia", 60, "NORMAL", "#F8FAFC", "CENTER", "MIDDLE", 1, 0, 0.0},
                {"Sermon", "COLOR", "#172033", "Segoe UI", 64, "BOLD", "#FFFFFF", "LEFT", "BOTTOM", 1, 0, 0.0},
                {"Announcement", "COLOR", "#1677FF", "Segoe UI", 64, "BOLD", "#FFFFFF", "CENTER", "MIDDLE", 1, 0, 0.0},
                {"Minimal", "COLOR", "#000000", "Segoe UI", 56, "NORMAL", "#FFFFFF", "CENTER", "MIDDLE", 0, 0, 0.0},
        };
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO themes(name, background_type, background_value, font_family, font_size, font_weight,
                                   font_color, text_align, text_position, shadow, outline, overlay_opacity,
                                   is_builtin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""")) {
            for (Object[] t : themes) {
                for (int i = 0; i < t.length; i++) {
                    ps.setObject(i + 1, t[i]);
                }
                ps.setString(13, now);
                ps.setString(14, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void insertSong(Connection conn, String title, String author, String copyright,
                                   String tags, String[][] sections) throws SQLException {
        String now = Instant.now().toString();
        long songId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO songs(title, author, copyright, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.setString(2, author);
            ps.setString(3, copyright);
            ps.setString(4, tags);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                songId = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO song_sections(song_id, section_type, label, content, sort_order) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < sections.length; i++) {
                ps.setLong(1, songId);
                ps.setString(2, sections[i][0]);
                ps.setString(3, sections[i][1]);
                ps.setString(4, sections[i][2]);
                ps.setInt(5, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * OBS-style default scenes: Worship, Camera, Camera + Lyrics, Bible, Sermon, Announcement,
     * Fullscreen Video, Logo / Welcome and Blank. Existing scenes keep their ids and layers; old names
     * are updated, the new Camera + Lyrics scene is added, and the defaults are protected from deletion.
     */
    static void applyV11(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        rename(conn, "Camera Only", "Camera");
        rename(conn, "Video", "Fullscreen Video");
        rename(conn, "Logo Screen", "Logo / Welcome");
        String full = "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FILL\"}";
        String logo = "{\"x\":0.86,\"y\":0.05,\"w\":0.1,\"h\":0.12,\"fit\":\"FIT\"}";

        Long camera = sceneId(conn, "Camera");
        if (sceneId(conn, "Camera + Lyrics") == null) {
            int order = 0;
            if (camera != null) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT sort_order FROM scenes WHERE id = ?")) {
                    ps.setLong(1, camera);
                    try (ResultSet rs = ps.executeQuery()) {
                        order = rs.next() ? rs.getInt(1) + 1 : 0;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET sort_order = sort_order + 1 WHERE sort_order >= ?")) {
                ps.setInt(1, order);
                ps.executeUpdate();
            }
            long id = insertScene(conn, "Camera + Lyrics", order, now);
            insertSources(conn, id, new String[][]{
                    {"Lower Third", "LOWER_THIRD", "1", full},
                    {"Church Logo", "CHURCH_LOGO", "1", logo},
                    {"Lyrics", "LYRICS", "1", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FILL\",\"textPosition\":\"BOTTOM\",\"textScale\":0.62}"},
                    {"Readability Overlay", "OVERLAY", "1", "{\"x\":0,\"y\":0.5,\"w\":1,\"h\":0.5,\"fit\":\"FILL\",\"opacity\":0.7}"},
                    {"Camera", "CAMERA", "1", full}}, 0);
        }
        Long bible = sceneId(conn, "Bible");
        if (bible != null && !hasSource(conn, bible, "CHURCH_LOGO")) {
            shiftSources(conn, bible, 1);
            insertSources(conn, bible, new String[][]{{"Church Logo", "CHURCH_LOGO", "1", logo}}, 1);
        }
        Long welcome = sceneId(conn, "Logo / Welcome");
        if (welcome != null) {
            if (!hasSource(conn, welcome, "TEXT")) {
                shiftSources(conn, welcome, 0);
                insertSources(conn, welcome, new String[][]{{"Welcome Text", "TEXT", "0",
                        "{\"x\":0.1,\"y\":0.78,\"w\":0.8,\"h\":0.12,\"text\":\"Welcome\",\"color\":\"#FFFFFF\",\"fontSize\":64}"}}, 0);
            }
            if (!hasSource(conn, welcome, "BACKGROUND")) {
                insertSources(conn, welcome, new String[][]{{"Background", "BACKGROUND", "1", full}}, 100);
            }
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE scenes SET is_builtin = 1 WHERE name IN ('Worship', 'Camera', 'Camera + Lyrics', 'Bible', "
                    + "'Sermon', 'Announcement', 'Fullscreen Video', 'Logo / Welcome', 'Blank')");
            // the standard order; the church's own scenes follow in their current order
            st.executeUpdate("UPDATE scenes SET sort_order = CASE name WHEN 'Worship' THEN 0 WHEN 'Camera' THEN 1 "
                    + "WHEN 'Camera + Lyrics' THEN 2 WHEN 'Bible' THEN 3 WHEN 'Sermon' THEN 4 WHEN 'Announcement' THEN 5 "
                    + "WHEN 'Fullscreen Video' THEN 6 WHEN 'Logo / Welcome' THEN 7 WHEN 'Blank' THEN 8 ELSE 100 + sort_order END");
        }
    }

    /**
     * Presentations as scene sources: the Sermon scene gets a DYNAMIC Presentation source (any presentation
     * sent from the library shows in it, so one Sermon scene serves every sermon), and a built-in
     * Camera + Presentation scene is added for the livestream (slide large, pastor camera in the corner).
     */
    static void applyV12(Connection conn) throws SQLException {
        String now = Instant.now().toString();
        String presentationFull = "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FIT\",\"presentationMode\":\"DYNAMIC\"}";
        Long sermon = sceneId(conn, "Sermon");
        if (sermon != null && !hasSource(conn, sermon, "PRESENTATION")) {
            Integer background = null;
            int max = -1;
            try (PreparedStatement ps = conn.prepareStatement("SELECT source_type, sort_order FROM sources WHERE scene_id = ?")) {
                ps.setLong(1, sermon);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        max = Math.max(max, rs.getInt(2));
                        if ("BACKGROUND".equals(rs.getString(1)) && (background == null || rs.getInt(2) < background)) {
                            background = rs.getInt(2);
                        }
                    }
                }
            }
            int at = background != null ? background : max + 1; // just above the slide background
            shiftSources(conn, sermon, at);
            insertSources(conn, sermon, new String[][]{{"Presentation", "PRESENTATION", "1", presentationFull}}, at);
        }
        if (sceneId(conn, "Camera + Presentation") == null) {
            int order = 5;
            try (PreparedStatement ps = conn.prepareStatement("SELECT sort_order FROM scenes WHERE name = 'Sermon'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        order = rs.getInt(1) + 1;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET sort_order = sort_order + 1 WHERE sort_order >= ?")) {
                ps.setInt(1, order);
                ps.executeUpdate();
            }
            long id = insertScene(conn, "Camera + Presentation", order, now);
            insertSources(conn, id, new String[][]{
                    {"Lower Third", "LOWER_THIRD", "1", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FILL\"}"},
                    {"Church Logo", "CHURCH_LOGO", "1", "{\"x\":0.86,\"y\":0.05,\"w\":0.1,\"h\":0.12,\"fit\":\"FIT\"}"},
                    {"Camera", "CAMERA", "1", "{\"x\":0.68,\"y\":0.64,\"w\":0.28,\"h\":0.28,\"fit\":\"FILL\",\"keepAspect\":true}"},
                    {"Presentation", "PRESENTATION", "1",
                            "{\"x\":0.03,\"y\":0.06,\"w\":0.63,\"h\":0.63,\"fit\":\"FIT\",\"presentationMode\":\"DYNAMIC\"}"},
                    {"Background", "BACKGROUND", "1", "{\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"fit\":\"FILL\"}"}}, 0);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET is_builtin = 1 WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    private static void rename(Connection conn, String from, String to) throws SQLException {
        if (sceneId(conn, to) != null) {
            return; // the church already has a scene with the new name
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET name = ? WHERE name = ?")) {
            ps.setString(1, to);
            ps.setString(2, from);
            ps.executeUpdate();
        }
    }

    private static Long sceneId(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM scenes WHERE name = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static boolean hasSource(Connection conn, long sceneId, String type) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sources WHERE scene_id = ? AND source_type = ?")) {
            ps.setLong(1, sceneId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Moves the layers at {@code from} and below one step down, freeing position {@code from}. */
    private static void shiftSources(Connection conn, long sceneId, int from) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sources SET sort_order = sort_order + 1 WHERE scene_id = ? AND sort_order >= ?")) {
            ps.setLong(1, sceneId);
            ps.setInt(2, from);
            ps.executeUpdate();
        }
    }

    private static long insertScene(Connection conn, String name, int order, String now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO scenes(name, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, order);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Inserts layers (top first) starting at sort position {@code firstOrder}. */
    private static void insertSources(Connection conn, long sceneId, String[][] sources, int firstOrder) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sources(scene_id, name, source_type, visible, sort_order, config_json) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int s = 0; s < sources.length; s++) {
                ps.setLong(1, sceneId);
                ps.setString(2, sources[s][0]);
                ps.setString(3, sources[s][1]);
                ps.setInt(4, Integer.parseInt(sources[s][2]));
                ps.setInt(5, firstOrder + s);
                ps.setString(6, sources[s][3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
