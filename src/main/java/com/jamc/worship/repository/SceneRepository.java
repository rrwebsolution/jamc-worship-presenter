package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceConfig;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQLite persistence for scenes and their sources. */
public class SceneRepository {

    private final DatabaseManager db;

    public SceneRepository(DatabaseManager db) {
        this.db = db;
    }

    /** All scenes with their sources, in display order. */
    public List<Scene> findAll() {
        return db.query(conn -> {
            Map<Long, Scene> scenes = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, is_builtin FROM scenes ORDER BY sort_order, id")) {
                while (rs.next()) {
                    Scene scene = new Scene(rs.getLong(1), rs.getString(2));
                    scene.setBuiltin(rs.getInt(3) != 0);
                    scenes.put(rs.getLong(1), scene);
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, scene_id, name, source_type, visible, config_json FROM sources ORDER BY scene_id, sort_order, id")) {
                while (rs.next()) {
                    Scene scene = scenes.get(rs.getLong("scene_id"));
                    if (scene != null) {
                        scene.getSources().add(mapSource(rs));
                    }
                }
            }
            return new ArrayList<>(scenes.values());
        });
    }

    public Optional<Scene> findById(long id) {
        return findAll().stream().filter(s -> s.getId() == id).findFirst();
    }

    /** Inserts or updates a scene and replaces its sources (source ids are reassigned). */
    public Scene save(Scene scene, int sortOrder) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            long sid;
            if (scene.getId() == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO scenes(name, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, scene.getName());
                    ps.setInt(2, sortOrder);
                    ps.setString(3, now);
                    ps.setString(4, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        sid = keys.getLong(1);
                    }
                }
            } else {
                sid = scene.getId();
                try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET name = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, scene.getName());
                    ps.setString(2, now);
                    ps.setLong(3, sid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sources WHERE scene_id = ?")) {
                    ps.setLong(1, sid);
                    ps.executeUpdate();
                }
            }
            insertSources(conn, sid, scene.getSources());
            return sid;
        });
        return findById(id).orElseThrow();
    }

    /** Persists the display order of scenes. */
    public void reorder(List<Long> sceneIds) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE scenes SET sort_order = ? WHERE id = ?")) {
                for (int i = 0; i < sceneIds.size(); i++) {
                    ps.setInt(1, i);
                    ps.setLong(2, sceneIds.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void delete(long id) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM scenes WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int count() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scenes")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static void insertSources(Connection conn, long sceneId, List<Source> sources) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sources(scene_id, name, source_type, visible, sort_order, config_json) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                ps.setLong(1, sceneId);
                ps.setString(2, s.getName());
                ps.setString(3, s.getType().name());
                ps.setInt(4, s.isVisible() ? 1 : 0);
                ps.setInt(5, i);
                ps.setString(6, Json.write(s.getConfig()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Source mapSource(ResultSet rs) throws SQLException {
        Source s = new Source(rs.getString("name"), SourceType.parse(rs.getString("source_type"), SourceType.TEXT));
        s.setId(rs.getLong("id"));
        s.setVisible(rs.getInt("visible") != 0);
        SourceConfig cfg = Json.readOrNull(rs.getString("config_json"), SourceConfig.class);
        s.setConfig(cfg == null ? new SourceConfig() : cfg);
        return s;
    }
}
