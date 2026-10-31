package com.jamc.worship.service;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns song sections into presentation slides.
 *
 * <p>Each section becomes one slide; a blank line inside a section starts another slide for the
 * same section, labelled "Chorus (1/2)", "Chorus (2/2)". Empty sections produce no slides. A song's
 * arrangement ("Verse 1|Chorus|Verse 2|Chorus") repeats sections without copying their lyrics.
 */
public final class SlideGenerator {

    private SlideGenerator() {
    }

    /** Slides in the song's arrangement (sections may repeat), or in written order if it has none. */
    public static List<Slide> fromSong(Song song) {
        List<Slide> slides = new ArrayList<>();
        if (song == null) {
            return slides;
        }
        Map<String, SongSection> byKey = sectionsByKey(song);
        for (String key : order(song, byKey)) {
            SongSection section = byKey.get(key);
            List<String> parts = splitOnBlankLines(section.getContent());
            for (int i = 0; i < parts.size(); i++) {
                String label = parts.size() == 1 ? section.getLabel()
                        : section.getLabel() + " (" + (i + 1) + "/" + parts.size() + ")";
                Slide slide = new Slide(label, parts.get(i), null);
                slide.setSectionKey(key);
                slide.setCardKey(cardKey(key, i));
                slides.add(slide);
            }
        }
        return slides;
    }

    /** "Verse 1/0", "Verse 1/1": a card's key for lyrics sync times. */
    public static String cardKey(String sectionKey, int index) {
        return sectionKey + "/" + index;
    }

    /** Every card of the song once, in written order (what sync status is measured against). */
    public static List<String> cardKeys(Song song) {
        List<String> keys = new ArrayList<>();
        if (song == null) {
            return keys;
        }
        for (Map.Entry<String, SongSection> e : sectionsByKey(song).entrySet()) {
            int n = splitOnBlankLines(e.getValue().getContent()).size();
            for (int i = 0; i < n; i++) {
                keys.add(cardKey(e.getKey(), i));
            }
        }
        return keys;
    }

    /** Every card of the song once, in written order, with its section key, index and text (for sync editors). */
    public static List<Slide> distinctCards(Song song) {
        List<Slide> cards = new ArrayList<>();
        if (song == null) {
            return cards;
        }
        for (Map.Entry<String, SongSection> e : sectionsByKey(song).entrySet()) {
            List<String> parts = splitOnBlankLines(e.getValue().getContent());
            for (int i = 0; i < parts.size(); i++) {
                Slide s = new Slide(e.getValue().getLabel() + (parts.size() == 1 ? "" : String.valueOf((char) ('A' + Math.min(i, 25)))),
                        parts.get(i), null);
                s.setSectionKey(e.getKey());
                s.setCardKey(cardKey(e.getKey(), i));
                cards.add(s);
            }
        }
        return cards;
    }

    /** Keys of the song's sections in written order: the label, plus "#2", "#3" for later sections with the same label. */
    public static List<String> sectionKeys(Song song) {
        return new ArrayList<>(sectionsByKey(song).keySet());
    }

    /** The presentation order: the stored arrangement (unknown keys skipped), else the written order. */
    public static List<String> order(Song song) {
        return order(song, sectionsByKey(song));
    }

    private static List<String> order(Song song, Map<String, SongSection> byKey) {
        List<String> out = new ArrayList<>();
        String arrangement = song.getArrangement();
        if (arrangement != null && !arrangement.isBlank()) {
            for (String key : arrangement.split("\\|")) {
                if (byKey.containsKey(key.strip())) {
                    out.add(key.strip());
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(byKey.keySet());
        }
        return out;
    }

    private static Map<String, SongSection> sectionsByKey(Song song) {
        Map<String, SongSection> out = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (SongSection section : song.getSections()) {
            if (splitOnBlankLines(section.getContent()).isEmpty()) {
                continue;
            }
            String label = section.getLabel() == null ? "Section" : section.getLabel().strip();
            int n = seen.merge(label.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            out.put(n == 1 ? label : label + "#" + n, section);
        }
        return out;
    }

    public static List<String> splitOnBlankLines(String content) {
        List<String> parts = new ArrayList<>();
        if (content == null) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    parts.add(current.toString().strip());
                    current.setLength(0);
                }
            } else {
                current.append(line.strip()).append('\n');
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }
}
