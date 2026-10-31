package com.jamc.worship.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A song in the library.
 *
 * <p>Instances returned by list queries are summaries: {@link #isSectionsLoaded()} is false
 * and {@link #getSections()} is empty until the song is loaded by id.
 */
public class Song {

    private Long id;
    private String title = "";
    private String author;
    private String copyright;
    private String ccliNumber;
    private String tags;
    private boolean favorite;
    private Long themeId;
    private String background;
    private String fontFamily;
    private Integer fontSize;
    private String arrangement;
    /** Song-only typography (weight, letter case, alignment...) over the global lyrics typography; null = none. */
    private StyleOverrides typography;
    private Instant createdAt;
    private Instant updatedAt;
    /**
     * Optional, secondary YouTube reference (the song stays text lyrics): the video it was identified from,
     * a linked YouTube Video Library item and section sync times. Presenting the song never shows the video.
     */
    private String youtubeVideoId;
    private String youtubeUrl;
    private Long youtubeMediaId;
    private LyricsSync sync;
    /** Provenance only (MANUAL, PASTED, FILE_IMPORT...); all lyrics are presented the same way. Null = unknown / older. */
    private LyricsSourceType sourceType;
    private String sourceProvider;
    private String sourceReference;
    private List<SongSection> sections = new ArrayList<>();
    private boolean sectionsLoaded = true;

    /** Deep copy without database identity, used for "Duplicate". */
    public Song copyWithoutId() {
        Song s = new Song();
        s.title = title;
        s.author = author;
        s.copyright = copyright;
        s.arrangement = arrangement;
        s.ccliNumber = ccliNumber;
        s.tags = tags;
        s.favorite = false;
        s.themeId = themeId;
        s.background = background;
        s.fontFamily = fontFamily;
        s.fontSize = fontSize;
        s.typography = typography == null ? null : typography.copy();
        s.youtubeVideoId = youtubeVideoId;
        s.youtubeUrl = youtubeUrl;
        s.youtubeMediaId = youtubeMediaId;
        s.sync = sync == null ? null : sync.copy();
        s.sourceType = sourceType;
        s.sourceProvider = sourceProvider;
        s.sourceReference = sourceReference;
        s.sections = new ArrayList<>(sections.stream().map(SongSection::copy).toList());
        return s;
    }

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = Objects.requireNonNullElse(title, ""); }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getCopyright() { return copyright; }
    public void setCopyright(String copyright) { this.copyright = copyright; }
    public String getCcliNumber() { return ccliNumber; }
    public void setCcliNumber(String ccliNumber) { this.ccliNumber = ccliNumber; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public Long getThemeId() { return themeId; }
    public void setThemeId(Long themeId) { this.themeId = themeId; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public Integer getFontSize() { return fontSize; }
    public void setFontSize(Integer fontSize) { this.fontSize = fontSize; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Section order for presenting, "Verse 1|Chorus|Verse 2|Chorus", or null for the written order. */
    public String getArrangement() { return arrangement; }
    public void setArrangement(String arrangement) { this.arrangement = arrangement; }
    public StyleOverrides getTypography() { return typography; }
    public void setTypography(StyleOverrides typography) { this.typography = typography == null || typography.isEmpty() ? null : typography; }
    public String getYoutubeVideoId() { return youtubeVideoId; }
    public void setYoutubeVideoId(String youtubeVideoId) { this.youtubeVideoId = youtubeVideoId; }
    public String getYoutubeUrl() { return youtubeUrl; }
    public void setYoutubeUrl(String youtubeUrl) { this.youtubeUrl = youtubeUrl; }
    public Long getYoutubeMediaId() { return youtubeMediaId; }
    public void setYoutubeMediaId(Long youtubeMediaId) { this.youtubeMediaId = youtubeMediaId; }
    public LyricsSourceType getSourceType() { return sourceType; }
    public void setSourceType(LyricsSourceType sourceType) { this.sourceType = sourceType; }
    public String getSourceProvider() { return sourceProvider; }
    public void setSourceProvider(String sourceProvider) { this.sourceProvider = sourceProvider; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }
    public LyricsSync getSync() { return sync; }
    public void setSync(LyricsSync sync) { this.sync = sync; }
    /** Whether a YouTube video is remembered for this song (shown as "YouTube Sync Available"). */
    public boolean hasYouTubeReference() { return youtubeVideoId != null && !youtubeVideoId.isBlank(); }
    public List<SongSection> getSections() { return sections; }
    public void setSections(List<SongSection> sections) { this.sections = new ArrayList<>(sections); }
    public boolean isSectionsLoaded() { return sectionsLoaded; }
    public void setSectionsLoaded(boolean sectionsLoaded) { this.sectionsLoaded = sectionsLoaded; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Song other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }

    @Override
    public String toString() {
        return title;
    }
}
