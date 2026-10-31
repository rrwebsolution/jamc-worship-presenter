package com.jamc.worship.model;

import java.util.Objects;

/** One labelled block of lyrics, e.g. "Verse 1" or "Chorus". */
public class SongSection {

    private Long id;
    private SectionType type;
    private String label;
    private String content;

    public SongSection() {
        this(SectionType.VERSE, "Verse 1", "");
    }

    public SongSection(SectionType type, String label, String content) {
        this.type = Objects.requireNonNull(type);
        this.label = label;
        this.content = content;
    }

    public SongSection copy() {
        return new SongSection(type, label, content);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public SectionType getType() { return type; }
    public void setType(SectionType type) { this.type = Objects.requireNonNull(type); }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    @Override
    public String toString() {
        return label;
    }
}
