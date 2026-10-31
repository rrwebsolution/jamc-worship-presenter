package com.jamc.worship.database;

/**
 * DDL for each schema version. Tables for later phases (services, media, scenes, ...)
 * are created up front so the file format stays stable while features are added.
 */
final class Schema {

    private Schema() {
    }

    static final String V1 = """
            CREATE TABLE themes (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                name             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                background_type  TEXT    NOT NULL DEFAULT 'COLOR',
                background_value TEXT,
                font_family      TEXT    NOT NULL DEFAULT 'Segoe UI',
                font_size        INTEGER NOT NULL DEFAULT 64,
                font_weight      TEXT    NOT NULL DEFAULT 'BOLD',
                font_color       TEXT    NOT NULL DEFAULT '#FFFFFF',
                text_align       TEXT    NOT NULL DEFAULT 'CENTER',
                text_position    TEXT    NOT NULL DEFAULT 'MIDDLE',
                shadow           INTEGER NOT NULL DEFAULT 1,
                outline          INTEGER NOT NULL DEFAULT 0,
                outline_color    TEXT    NOT NULL DEFAULT '#000000',
                overlay_opacity  REAL    NOT NULL DEFAULT 0.0,
                is_builtin       INTEGER NOT NULL DEFAULT 0,
                created_at       TEXT    NOT NULL,
                updated_at       TEXT    NOT NULL
            );

            CREATE TABLE songs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                title       TEXT    NOT NULL,
                author      TEXT,
                copyright   TEXT,
                ccli_number TEXT,
                tags        TEXT,
                theme_id    INTEGER REFERENCES themes(id) ON DELETE SET NULL,
                background  TEXT,
                font_family TEXT,
                font_size   INTEGER,
                created_at  TEXT    NOT NULL,
                updated_at  TEXT    NOT NULL
            );
            CREATE INDEX idx_songs_title ON songs(title COLLATE NOCASE);

            CREATE TABLE song_sections (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                song_id      INTEGER NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                section_type TEXT    NOT NULL,
                label        TEXT    NOT NULL,
                content      TEXT    NOT NULL,
                sort_order   INTEGER NOT NULL
            );
            CREATE INDEX idx_song_sections_song ON song_sections(song_id, sort_order);

            CREATE TABLE services (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                name         TEXT    NOT NULL,
                playlist     TEXT,
                service_date TEXT,
                is_template  INTEGER NOT NULL DEFAULT 0,
                notes        TEXT,
                created_at   TEXT    NOT NULL,
                updated_at   TEXT    NOT NULL
            );

            CREATE TABLE service_items (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,
                item_type  TEXT    NOT NULL,
                title      TEXT    NOT NULL,
                ref_id     INTEGER,
                payload    TEXT,
                sort_order INTEGER NOT NULL
            );
            CREATE INDEX idx_service_items_service ON service_items(service_id, sort_order);

            CREATE TABLE slides (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_type TEXT    NOT NULL,
                owner_id   INTEGER,
                sort_order INTEGER NOT NULL,
                content    TEXT,
                background TEXT,
                style_json TEXT,
                created_at TEXT    NOT NULL,
                updated_at TEXT    NOT NULL
            );
            CREATE INDEX idx_slides_owner ON slides(owner_type, owner_id, sort_order);

            CREATE TABLE media (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                media_type  TEXT    NOT NULL,
                file_path   TEXT    NOT NULL,
                is_copied   INTEGER NOT NULL DEFAULT 0,
                file_size   INTEGER,
                duration_ms INTEGER,
                width       INTEGER,
                height      INTEGER,
                created_at  TEXT    NOT NULL,
                updated_at  TEXT    NOT NULL
            );
            CREATE INDEX idx_media_type ON media(media_type);

            CREATE TABLE scenes (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL,
                sort_order INTEGER NOT NULL,
                created_at TEXT    NOT NULL,
                updated_at TEXT    NOT NULL
            );

            CREATE TABLE sources (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                scene_id    INTEGER NOT NULL REFERENCES scenes(id) ON DELETE CASCADE,
                name        TEXT    NOT NULL,
                source_type TEXT    NOT NULL,
                visible     INTEGER NOT NULL DEFAULT 1,
                sort_order  INTEGER NOT NULL,
                config_json TEXT
            );
            CREATE INDEX idx_sources_scene ON sources(scene_id, sort_order);

            CREATE TABLE lower_thirds (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                name             TEXT    NOT NULL,
                title            TEXT    NOT NULL,
                subtitle         TEXT,
                font_family      TEXT,
                font_color       TEXT,
                background_color TEXT,
                logo_path        TEXT,
                position         TEXT    NOT NULL DEFAULT 'BOTTOM_LEFT',
                animation        TEXT    NOT NULL DEFAULT 'SLIDE',
                created_at       TEXT    NOT NULL,
                updated_at       TEXT    NOT NULL
            );

            CREATE TABLE favorites (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                item_type  TEXT    NOT NULL,
                item_id    INTEGER NOT NULL,
                created_at TEXT    NOT NULL,
                UNIQUE (item_type, item_id)
            );

            CREATE TABLE settings (
                key        TEXT PRIMARY KEY,
                value      TEXT,
                updated_at TEXT NOT NULL
            )
            """;

    /**
     * Bible versions and their text providers. Copyrighted versions are listed but start disabled
     * with no text: the church connects its own licensed file or API.
     */
    /**
     * Where a song's lyrics came from (MANUAL, PASTED, FILE_IMPORT, LICENSED_API, AUTHORIZED_PROVIDER,
     * YOUTUBE_REFERENCE), which provider and its reference. The source never changes how lyrics are presented.
     */
    static final String V14 = """
            ALTER TABLE songs ADD COLUMN source_type TEXT;
            ALTER TABLE songs ADD COLUMN source_provider TEXT;
            ALTER TABLE songs ADD COLUMN source_reference TEXT
            """;

    /**
     * YouTube. Video library items can reference a YouTube video instead of a file (source_type YOUTUBE,
     * file_path 'youtube:ID'; nothing is downloaded). Songs stay text lyrics and may only remember the
     * YouTube video they were identified from, a linked YouTube Video Library item and sync times.
     */
    static final String V13 = """
            ALTER TABLE media ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL';
            ALTER TABLE media ADD COLUMN youtube_id TEXT;
            ALTER TABLE media ADD COLUMN channel_name TEXT;
            ALTER TABLE media ADD COLUMN thumbnail_url TEXT;
            CREATE INDEX idx_media_youtube ON media(youtube_id);
            ALTER TABLE songs ADD COLUMN youtube_video_id TEXT;
            ALTER TABLE songs ADD COLUMN youtube_url TEXT;
            ALTER TABLE songs ADD COLUMN youtube_media_id INTEGER;
            ALTER TABLE songs ADD COLUMN sync_json TEXT
            """;

    /** Scenes the app installs are marked built-in (OBS-style default scenes, see SeedData.applyV11). */
    static final String V11 = """
            ALTER TABLE scenes ADD COLUMN is_builtin INTEGER NOT NULL DEFAULT 0
            """;

    /**
     * Typography: text case and shadow strength per theme, whether a theme overrides the global
     * typography profiles, and per-song typography overrides (JSON). font_weight now also holds
     * LIGHT, MEDIUM, SEMIBOLD... besides the old NORMAL / BOLD.
     */
    static final String V10 = """
            ALTER TABLE themes ADD COLUMN text_transform TEXT NOT NULL DEFAULT 'ORIGINAL';
            ALTER TABLE themes ADD COLUMN shadow_strength REAL NOT NULL DEFAULT 0.55;
            ALTER TABLE themes ADD COLUMN typography_override INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE songs ADD COLUMN style_json TEXT
            """;

    /** Per-theme behavior towards the operator's global video background. */
    static final String V9 = """
            ALTER TABLE themes ADD COLUMN background_mode TEXT NOT NULL DEFAULT 'USE_GLOBAL'
            """;

    /** Song section order (arrangement), edited in the Action / Order list. */
    static final String V8 = """
            ALTER TABLE songs ADD COLUMN arrangement TEXT
            """;

    /** Background Fit / Fill for image and video backgrounds. */
    static final String V7 = """
            ALTER TABLE themes ADD COLUMN background_fit TEXT NOT NULL DEFAULT 'FILL'
            """;

    /** Imported PowerPoint presentations; their rendered slides live in data/presentations/&lt;folder&gt;. */
    static final String V6 = """
            CREATE TABLE presentations (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                name            TEXT    NOT NULL,
                source_path     TEXT    NOT NULL,
                folder          TEXT    NOT NULL UNIQUE,
                slide_count     INTEGER NOT NULL DEFAULT 0,
                renderer        TEXT,
                source_size     INTEGER NOT NULL DEFAULT 0,
                source_modified INTEGER NOT NULL DEFAULT 0,
                warning_count   INTEGER NOT NULL DEFAULT 0,
                imported_at     TEXT    NOT NULL,
                updated_at      TEXT    NOT NULL
            );
            CREATE INDEX idx_presentations_name ON presentations(name COLLATE NOCASE)
            """;

    static final String V5 = """
            CREATE TABLE bible_versions (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                abbreviation    TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                name            TEXT    NOT NULL,
                language        TEXT,
                provider        TEXT    NOT NULL DEFAULT 'NONE',
                local_file      TEXT,
                enabled         INTEGER NOT NULL DEFAULT 0,
                provider_config TEXT,
                sort_order      INTEGER NOT NULL DEFAULT 0
            );
            INSERT INTO bible_versions(abbreviation, name, language, provider, enabled, sort_order)
                VALUES ('KJV', 'King James Version', 'English', 'PUBLIC_DOMAIN', 1, 0);
            INSERT INTO bible_versions(abbreviation, name, language, provider, enabled, sort_order)
                VALUES ('NLT', 'New Living Translation', 'English', 'NONE', 0, 1);
            INSERT INTO bible_versions(abbreviation, name, language, provider, enabled, sort_order)
                VALUES ('ESV', 'English Standard Version', 'English', 'NONE', 0, 2);
            INSERT INTO bible_versions(abbreviation, name, language, provider, enabled, sort_order)
                VALUES ('NIV', 'New International Version', 'English', 'NONE', 0, 3);
            INSERT INTO bible_versions(abbreviation, name, language, provider, enabled, sort_order)
                VALUES ('APSD', 'Ang Pulong Sa Dios', 'Cebuano', 'NONE', 0, 4);
            INSERT OR IGNORE INTO bible_versions(abbreviation, name, language, provider, local_file, enabled, sort_order)
                SELECT code, name, language, 'LOCAL_FILE', source, 1, 10 FROM bible_translations
            """;

    /** Phases 4-8: media categories, Bible text, lower-third styling. */
    static final String V4 = """
            ALTER TABLE media ADD COLUMN category TEXT NOT NULL DEFAULT 'IMAGE';
            CREATE INDEX idx_media_category ON media(category);

            ALTER TABLE lower_thirds ADD COLUMN title_color TEXT NOT NULL DEFAULT '#FFFFFF';
            ALTER TABLE lower_thirds ADD COLUMN accent_color TEXT NOT NULL DEFAULT '#1677FF';
            ALTER TABLE lower_thirds ADD COLUMN font_size INTEGER NOT NULL DEFAULT 52;

            CREATE TABLE bible_translations (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                code        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                name        TEXT    NOT NULL,
                language    TEXT,
                source      TEXT,
                imported_at TEXT    NOT NULL
            );

            CREATE TABLE bible_verses (
                translation_id INTEGER NOT NULL REFERENCES bible_translations(id) ON DELETE CASCADE,
                book           INTEGER NOT NULL,
                chapter        INTEGER NOT NULL,
                verse          INTEGER NOT NULL,
                text           TEXT    NOT NULL,
                PRIMARY KEY (translation_id, book, chapter, verse)
            ) WITHOUT ROWID;

            CREATE INDEX idx_services_playlist ON services(playlist, updated_at)
            """;

    /** Phase 2: richer themes and custom slide decks. */
    static final String V3 = """
            ALTER TABLE themes ADD COLUMN italic INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE themes ADD COLUMN line_spacing REAL NOT NULL DEFAULT 1.1;
            ALTER TABLE themes ADD COLUMN outline_width INTEGER NOT NULL DEFAULT 4;
            ALTER TABLE themes ADD COLUMN background_color TEXT NOT NULL DEFAULT '#000000';

            ALTER TABLE slides ADD COLUMN label TEXT;
            ALTER TABLE slides ADD COLUMN caption TEXT;

            CREATE TABLE slide_decks (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL,
                theme_id   INTEGER REFERENCES themes(id) ON DELETE SET NULL,
                created_at TEXT    NOT NULL,
                updated_at TEXT    NOT NULL
            )
            """;
}
