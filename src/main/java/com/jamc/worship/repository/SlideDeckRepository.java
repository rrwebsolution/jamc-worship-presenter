package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
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

/** SQLite persistence for custom slide decks and their slides ({@code slides.owner_type = 'DECK'}). */
public class SlideDeckRepository {

    static final String OWNER_TYPE = "DECK";

    private final DatabaseManager db;

    public SlideDeckRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Deck summaries (no slides) whose name contains {@code text}, alphabetical. */
    public List<SlideDeck> findAll(String text) {
        String filter = text == null ? "" : text.strip();
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.id, d.name, d.theme_id, d.updated_at,
                           (SELECT COUNT(*) FROM slides s WHERE s.owner_type = 'DECK' AND s.owner_id = d.id) AS slide_count
                    FROM slide_decks d
                    WHERE ? = '' OR d.name LIKE ? ESCAPE '\\'
                       OR EXISTS (SELECT 1 FROM slides s WHERE s.owner_type = 'DECK' AND s.owner_id = d.id
                                  AND s.content LIKE ? ESCAPE '\\')
                    ORDER BY d.name COLLATE NOCASE""")) {
                String like = "%" + SongRepository.escapeLike(filter) + "%";
                ps.setString(1, filter);
                ps.setString(2, like);
                ps.setString(3, like);
                List<SlideDeck> decks = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SlideDeck deck = mapDeck(rs);
                        deck.setSlideCount(rs.getInt("slide_count"));
                        decks.add(deck);
                    }
                }
                return decks;
            }
        });
    }

    public Optional<SlideDeck> findById(long id) {
        return db.query(conn -> {
            SlideDeck deck;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, name, theme_id, updated_at FROM slide_decks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    deck = mapDeck(rs);
                }
            }
            deck.setSlides(loadSlides(conn, id));
            deck.setSlideCount(deck.getSlides().size());
            return Optional.of(deck);
        });
    }

    public SlideDeck insert(SlideDeck deck) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            long newId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO slide_decks(name, theme_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, deck.getName().strip());
                setNullableLong(ps, 2, deck.getThemeId());
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    newId = keys.getLong(1);
                }
            }
            insertSlides(conn, newId, deck.getSlides());
            return newId;
        });
        return findById(id).orElseThrow();
    }

    /** Saves the deck header and replaces all of its slides. */
    public SlideDeck update(SlideDeck deck) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE slide_decks SET name = ?, theme_id = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, deck.getName().strip());
                setNullableLong(ps, 2, deck.getThemeId());
                ps.setString(3, Instant.now().toString());
                ps.setLong(4, deck.getId());
                if (ps.executeUpdate() == 0) {
                    throw new SQLException("Slide deck " + deck.getId() + " no longer exists");
                }
            }
            deleteSlides(conn, deck.getId());
            insertSlides(conn, deck.getId(), deck.getSlides());
            return null;
        });
        return findById(deck.getId()).orElseThrow();
    }

    public boolean delete(long id) {
        return db.inTransaction(conn -> {
            deleteSlides(conn, id);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM slide_decks WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean nameExists(String name, Long excludeId) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM slide_decks WHERE name = ? COLLATE NOCASE AND id <> ? LIMIT 1")) {
                ps.setString(1, name.strip());
                ps.setLong(2, excludeId == null ? -1 : excludeId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private static List<Slide> loadSlides(Connection conn, long deckId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, label, content, caption, style_json FROM slides
                WHERE owner_type = 'DECK' AND owner_id = ? ORDER BY sort_order, id""")) {
            ps.setLong(1, deckId);
            List<Slide> slides = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Slide slide = new Slide(rs.getString("label"), rs.getString("content"), rs.getString("caption"));
                    slide.setId(rs.getLong("id"));
                    slide.setOverrides(Json.readOrNull(rs.getString("style_json"), StyleOverrides.class));
                    slides.add(slide);
                }
            }
            return slides;
        }
    }

    private static void insertSlides(Connection conn, long deckId, List<Slide> slides) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO slides(owner_type, owner_id, sort_order, label, content, caption, style_json, created_at, updated_at)
                VALUES ('DECK', ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (int i = 0; i < slides.size(); i++) {
                Slide slide = slides.get(i);
                ps.setLong(1, deckId);
                ps.setInt(2, i);
                ps.setString(3, slide.getLabel());
                ps.setString(4, slide.getText());
                ps.setString(5, slide.getCaption() == null || slide.getCaption().isBlank() ? null : slide.getCaption());
                StyleOverrides o = slide.getOverrides();
                ps.setString(6, o == null || o.isEmpty() ? null : Json.write(o));
                ps.setString(7, now);
                ps.setString(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void deleteSlides(Connection conn, long deckId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM slides WHERE owner_type = 'DECK' AND owner_id = ?")) {
            ps.setLong(1, deckId);
            ps.executeUpdate();
        }
    }

    private static SlideDeck mapDeck(ResultSet rs) throws SQLException {
        SlideDeck deck = new SlideDeck();
        deck.setId(rs.getLong("id"));
        deck.setName(rs.getString("name"));
        long themeId = rs.getLong("theme_id");
        deck.setThemeId(rs.wasNull() ? null : themeId);
        try {
            String updated = rs.getString("updated_at");
            deck.setUpdatedAt(updated == null ? null : Instant.parse(updated));
        } catch (DateTimeParseException e) {
            deck.setUpdatedAt(null);
        }
        return deck;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }
}
