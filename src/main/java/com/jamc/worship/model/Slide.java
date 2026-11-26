package com.jamc.worship.model;

/**
 * One presentation slide: text plus optional per-slide style overrides.
 *
 * <p>Song slides are generated from sections and never stored; custom slides belong to a
 * {@link SlideDeck} and are persisted in the {@code slides} table.
 */
public class Slide {

    private Long id;
    private String label;
    private String text;
    private String caption;
    private String heading;
    private String thumbnail;
    private String sectionKey;
    /** "Verse 1/0": the section key and this card's index in it (lyrics sync times are kept per card). */
    private String cardKey;
    private boolean coversBackground;
    private StyleOverrides overrides;
    private Long presentationId;
    private int presentationIndex = -1;
    /** Song slides: the library song they come from (a scene source fixed to one song shows only its slides). */
    private Long songId;

    public Slide(String label, String text, String caption) {
        this.label = label;
        this.text = text == null ? "" : text;
        this.caption = caption;
    }

    public Slide copy() {
        Slide s = new Slide(label, text, caption);
        s.heading = heading;
        s.thumbnail = thumbnail;
        s.sectionKey = sectionKey;
        s.cardKey = cardKey;
        s.coversBackground = coversBackground;
        s.overrides = overrides == null ? null : overrides.copy();
        s.presentationId = presentationId;
        s.presentationIndex = presentationIndex;
        s.songId = songId;
        s.bible = bible;
        return s;
    }

    /** A Bible verse slide (made by the Bible page): a scene's Bible source shows only these. */
    private boolean bible;

    public boolean isBible() { return bible; }
    public void setBible(boolean bible) { this.bible = bible; }

    /** Resolves this slide's final look on top of {@code base}. */
    public SlideStyle styleOver(SlideStyle base) {
        return overrides == null || overrides.isEmpty() ? base : overrides.applyTo(base);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("\\s*\\(\\d+/\\d+\\)\\s*$");

    /**
     * The label to show people: a song card that is one part of a longer section shows just its section,
     * "Verse 1" (not "Verse 1 (1/2)"), which confused operators. The stored label keeps the part, which groups the
     * cards. Other slides (Bible, decks) are shown as they are.
     */
    public String displayLabel() {
        return songCardLabel(label, sectionKey != null || songId != null);
    }

    /**
     * True for the 2nd, 3rd… card of one song section ("Verse 1 (2/2)"): card lists show the section name only on
     * its first card, so "VERSE 1" is not repeated on every card under it.
     */
    public boolean continuesSection() {
        if (label == null || (sectionKey == null && songId == null)) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)/\\d+\\)\\s*$").matcher(label);
        return m.find() && Integer.parseInt(m.group(1)) > 1;
    }

    /** The label for a card in a list: blank on a section's later cards, else {@link #displayLabel()}. */
    public String cardLabel() {
        return continuesSection() ? "" : displayLabel();
    }

    /** {@code label} without a trailing "(1/2)" part when it belongs to a song card. */
    public static String songCardLabel(String label, boolean songCard) {
        return label == null || !songCard ? label : PART.matcher(label).replaceFirst("");
    }
    public String getText() { return text; }
    public void setText(String text) { this.text = text == null ? "" : text; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    /** Optional line above the text, e.g. the Bible reference "JOHN 3:16". */
    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }
    /** Absolute path of a pre-rendered small picture of this slide (imported presentations), or null. */
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    /** Song slides: which section they come from ("Chorus", or "Chorus#2" for a second section with that name). */
    public String getSectionKey() { return sectionKey; }
    public void setSectionKey(String sectionKey) { this.sectionKey = sectionKey; }
    public String getCardKey() { return cardKey; }
    public void setCardKey(String cardKey) { this.cardKey = cardKey; }
    /**
     * Full-screen pictures (PowerPoint slides, images) cover the global video background instead of
     * sitting on it; the background keeps running underneath.
     */
    public boolean coversBackground() { return coversBackground; }
    public void setCoversBackground(boolean covers) { this.coversBackground = covers; }
    public StyleOverrides getOverrides() { return overrides; }
    public void setOverrides(StyleOverrides overrides) { this.overrides = overrides; }
    /**
     * Library presentation this slide comes from (null for other slides), so a scene's Presentation
     * source can show it inside its own box instead of full screen.
     */
    public Long getPresentationId() { return presentationId; }
    /** 0-based slide number within {@link #getPresentationId()}, or -1. */
    public int getPresentationIndex() { return presentationIndex; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public void setPresentation(Long id, int index) {
        this.presentationId = id;
        this.presentationIndex = id == null ? -1 : index;
    }

    @Override
    public String toString() {
        return label;
    }
}
