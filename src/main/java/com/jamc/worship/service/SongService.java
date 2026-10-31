package com.jamc.worship.service;

import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.repository.SongRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Song use cases for the UI. Methods block on the database, so call them from a background
 * worker, never from the Swing event dispatch thread.
 */
public class SongService {

    private static final Logger log = LoggerFactory.getLogger(SongService.class);
    public static final int MAX_TITLE_LENGTH = 200;

    private final SongRepository repository;

    public SongService(SongRepository repository) {
        this.repository = repository;
    }

    public List<Song> search(SongQuery query) {
        return repository.findAll(query);
    }

    public Optional<Song> load(long id) {
        return repository.findById(id);
    }

    public int count() {
        return repository.count();
    }

    /** Inserts or updates the song after validation and returns the stored copy. */
    public Song save(Song song) {
        validate(song);
        Song saved = song.getId() == null ? repository.insert(song) : repository.update(song);
        log.info("{} song #{} '{}' ({} sections)", song.getId() == null ? "Created" : "Updated",
                saved.getId(), saved.getTitle(), saved.getSections().size());
        return saved;
    }

    public boolean delete(long id) {
        boolean deleted = repository.delete(id);
        log.info("Deleted song #{}: {}", id, deleted);
        return deleted;
    }

    /**
     * Stores the order in which sections are presented, as section keys from
     * {@link SlideGenerator#sectionKeys(Song)} (repeats allowed); null or blank restores the written order.
     */
    /**
     * Remembers (or with a null video forgets) the YouTube video a song was identified from, an optional
     * linked YouTube Video Library item and sync times. The song itself stays text lyrics.
     */
    public void setYouTubeReference(long songId, String videoId, Long linkedMediaId, com.jamc.worship.model.LyricsSync sync) {
        repository.setYouTubeReference(songId, videoId, videoId == null ? null : com.jamc.worship.youtube.YouTubeRef.watchUrl(videoId),
                linkedMediaId, sync);
    }

    /** Every song follows Settings → Lyrics Typography again (their own font settings are removed). Returns how many changed. */
    public int useGlobalTypographyForAll() {
        int n = repository.clearAllTypography();
        log.info("Removed the own font settings of {} songs: all follow the global lyrics typography", n);
        return n;
    }

    /** Saves the Sync Lyrics times only. */
    public void setSync(long songId, com.jamc.worship.model.LyricsSync sync) {
        repository.setSync(songId, sync);
    }

    public void setArrangement(long songId, java.util.List<String> keys) {
        String value = keys == null || keys.isEmpty() ? null : String.join("|", keys);
        repository.setArrangement(songId, value);
    }

    /**
     * Saves the song's quick typography (font, size, weight, letter case, alignment) without
     * touching its lyrics. Null values follow the theme and the global lyrics typography.
     */
    public void setTypography(long songId, String fontFamily, Integer fontSize, com.jamc.worship.model.StyleOverrides typography) {
        repository.setTypography(songId, fontFamily, fontSize, typography);
    }

    /** Copies a song under a unique "(Copy)" title and returns the new song. */
    public Song duplicate(long id) {
        Song original = repository.findById(id)
                .orElseThrow(() -> new ValidationException("That song no longer exists."));
        Song copy = original.copyWithoutId();
        copy.setTitle(uniqueCopyTitle(original.getTitle()));
        Song saved = repository.insert(copy);
        if (original.getArrangement() != null) {
            repository.setArrangement(saved.getId(), original.getArrangement());
            saved.setArrangement(original.getArrangement());
        }
        log.info("Duplicated song #{} as #{} '{}'", id, saved.getId(), saved.getTitle());
        return saved;
    }

    public void setFavorite(long id, boolean favorite) {
        repository.setFavorite(id, favorite);
    }

    /** True when another song already uses this title; the editor warns but still allows it. */
    public boolean isDuplicateTitle(String title, Long excludeId) {
        return title != null && !title.isBlank() && repository.titleExists(title, excludeId);
    }

    void validate(Song song) {
        String title = song.getTitle() == null ? "" : song.getTitle().strip();
        if (title.isEmpty()) {
            throw new ValidationException("Please enter a song title.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("The title is too long (maximum " + MAX_TITLE_LENGTH + " characters).");
        }
        if (song.getFontSize() != null && (song.getFontSize() < 8 || song.getFontSize() > 400)) {
            throw new ValidationException("Font size must be between 8 and 400.");
        }
        song.setTitle(title);
    }

    private String uniqueCopyTitle(String title) {
        String base = title + " (Copy)";
        String candidate = base;
        for (int n = 2; repository.titleExists(candidate, null); n++) {
            candidate = title + " (Copy " + n + ")";
        }
        return candidate;
    }
}
