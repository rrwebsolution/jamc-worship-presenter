package com.jamc.worship.model;

import java.util.List;
import java.util.Objects;

/**
 * One entry in a service rundown.
 *
 * <p>{@code refId} points at a library row (song, slide deck, media, lower third). {@code payload}
 * carries type-specific data: the Bible passage (JSON), or the text of an announcement/sermon item.
 */
public class ServiceItem {

    public enum Type {
        SONG("Song", "music"), BIBLE("Bible", "book"), IMAGE("Image", "image"), VIDEO("Video", "video"),
        AUDIO("Audio", "music"), SLIDE("Slides", "slides"), ANNOUNCEMENT("Announcement", "slides"),
        SERMON("Sermon", "slides"), LOWER_THIRD("Lower Third", "lower-third"), BLANK("Blank", "monitor"),
        PRESENTATION("Presentation", "presentation");

        private final String displayName;
        private final String icon;

        Type(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String displayName() {
            return displayName;
        }

        public String icon() {
            return icon;
        }

        public static Type parse(String v) {
            try {
                return v == null ? BLANK : valueOf(v);
            } catch (IllegalArgumentException e) {
                return BLANK;
            }
        }
    }

    private Long id;
    private Type type;
    private String title;
    private Long refId;
    private String payload;
    private String tag;

    /** Tags offered in the schedule's right-click menu; any other text is allowed too. */
    public static final List<String> COMMON_TAGS = List.of("Fast", "Slow", "Opening", "Worship", "Offering", "Closing");

    public ServiceItem(Type type, String title, Long refId) {
        this.type = Objects.requireNonNull(type);
        this.title = title;
        this.refId = refId;
    }

    /** A song item; a "Fast" or "Slow" song tag becomes the item's tag. */
    public static ServiceItem ofSong(Song song) {
        ServiceItem item = new ServiceItem(Type.SONG, song.getTitle(), song.getId());
        for (String t : song.tagList()) {
            if (t.equalsIgnoreCase("Fast") || t.equalsIgnoreCase("Slow")) {
                item.setTag(t.equalsIgnoreCase("Fast") ? "Fast" : "Slow");
                break;
            }
        }
        return item;
    }

    public ServiceItem copy() {
        ServiceItem c = new ServiceItem(type, title, refId);
        c.payload = payload;
        c.tag = tag;
        return c;
    }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag == null || tag.isBlank() ? null : tag.strip(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = Objects.requireNonNull(type); }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    @Override
    public String toString() {
        return title;
    }
}
