package com.jamc.worship.repository;

import com.jamc.worship.bible.BibleImporter;
import com.jamc.worship.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite storage for imported Bible translations. */
public class BibleRepository {

    public record Translation(long id, String code, String name, String language, String source) {
        @Override
        public String toString() {
            return code + " — " + name;
        }
    }

    /** A verse as stored. */
    public record StoredVerse(int book, int chapter, int verse, String text) {
    }

    private final DatabaseManager db;

    public BibleRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<Translation> translations() {
        return db.query(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, code, name, language, source FROM bible_translations ORDER BY code")) {
                List<Translation> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Translation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
                return out;
            }
        });
    }

    public Optional<Translation> find(String code) {
        return translations().stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst();
    }

    /** Stores a translation, replacing any existing one with the same code. Returns the verse count. */
    public int save(BibleImporter.ImportedBible bible, String source) {
        return db.inTransaction(conn -> {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM bible_translations WHERE code = ? COLLATE NOCASE")) {
                del.setString(1, bible.code());
                del.executeUpdate();
            }
            long id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO bible_translations(code, name, language, source, imported_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, bible.code());
                ps.setString(2, bible.name());
                ps.setString(3, bible.language());
                ps.setString(4, source);
                ps.setString(5, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO bible_verses(translation_id, book, chapter, verse, text) VALUES (?, ?, ?, ?, ?)")) {
                int n = 0;
                for (BibleImporter.Verse v : bible.verses()) {
                    ps.setLong(1, id);
                    ps.setInt(2, v.book());
                    ps.setInt(3, v.chapter());
                    ps.setInt(4, v.verse());
                    ps.setString(5, v.text());
                    ps.addBatch();
                    if (++n % 2000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            return bible.verses().size();
        });
    }

    public void delete(long translationId) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bible_translations WHERE id = ?")) {
                ps.setLong(1, translationId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int chapterCount(long translationId, int book) {
        return intQuery("SELECT COALESCE(MAX(chapter), 0) FROM bible_verses WHERE translation_id = ? AND book = ?",
                translationId, book);
    }

    public int verseCount(long translationId, int book, int chapter) {
        return intQuery("SELECT COALESCE(MAX(verse), 0) FROM bible_verses WHERE translation_id = ? AND book = ? AND chapter = ?",
                translationId, book, chapter);
    }

    public List<StoredVerse> verses(long translationId, int book, int chapter, int from, int to) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT book, chapter, verse, text FROM bible_verses
                    WHERE translation_id = ? AND book = ? AND chapter = ? AND verse BETWEEN ? AND ?
                    ORDER BY verse""")) {
                ps.setLong(1, translationId);
                ps.setInt(2, book);
                ps.setInt(3, chapter);
                ps.setInt(4, from);
                ps.setInt(5, to);
                return readVerses(ps);
            }
        });
    }

    /** Verses containing every word of {@code text} (case-insensitive), in canonical order. */
    public List<StoredVerse> search(long translationId, String text, int limit) {
        String[] words = text.strip().split("\\s+");
        StringBuilder sql = new StringBuilder("SELECT book, chapter, verse, text FROM bible_verses WHERE translation_id = ?");
        for (int i = 0; i < words.length; i++) {
            sql.append(" AND text LIKE ? ESCAPE '\\'");
        }
        sql.append(" ORDER BY book, chapter, verse LIMIT ?");
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setLong(i++, translationId);
                for (String w : words) {
                    ps.setString(i++, "%" + SongRepository.escapeLike(w) + "%");
                }
                ps.setInt(i, limit);
                return readVerses(ps);
            }
        });
    }

    private static List<StoredVerse> readVerses(PreparedStatement ps) throws java.sql.SQLException {
        List<StoredVerse> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new StoredVerse(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
            }
        }
        return out;
    }

    private int intQuery(String sql, Object... params) {
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
