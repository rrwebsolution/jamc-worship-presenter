package com.jamc.worship.service;

import com.jamc.worship.model.SongSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tidies lyrics for presenting without changing their words: AUTO FORMAT (recognised section labels in
 * one style), SPLIT INTO CARDS (a few lines per presentation card), card editing (merge, split, reorder) and
 * giving cards the times of a timed lyrics file.
 *
 * <p>Text format: section headings ("Verse 1", "Chorus"...) on their own line; a blank line inside a section
 * starts the next card.
 */
public final class LyricsFormatter {

    private static final Pattern INLINE_HEADING = Pattern.compile(
            "^\\s*[\\[(]?\\s*((?:verse|pre[- ]?chorus|chorus|bridge|intro|outro|tag|ending|instrumental|interlude)\\s*\\d*)\\s*[\\])]?\\s*[:\\-–]\\s+(\\S.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_HEADING = Pattern.compile("^\\s*\\(\\s*([A-Za-z][A-Za-z -]*\\s*\\d*)\\s*\\)\\s*$");
    private static final Map<String, String> NUMBER_WORDS = Map.of("one", "1", "two", "2", "three", "3", "four", "4",
            "five", "5", "six", "6", "seven", "7", "eight", "8");

    private LyricsFormatter() {
    }

    /**
     * Normalises the layout: section labels written as "[Chorus]", "CHORUS:", "(Bridge)", "Verse One" or
     * "Chorus: first line" become plain headings, extra blank lines and spaces at line ends go, and lyrics
     * without any labels are split into verses at blank lines. The words themselves are not changed.
     */
    public static String autoFormat(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split("\n", -1)) {
            String line = raw.stripTrailing();
            Matcher inline = INLINE_HEADING.matcher(line);
            if (inline.matches()) {
                out.add(heading(inline.group(1)));
                out.add(inline.group(2).strip());
                continue;
            }
            Matcher paren = PAREN_HEADING.matcher(line);
            if (paren.matches() && LyricsParser.parseHeading(paren.group(1)).isPresent()) {
                out.add(heading(paren.group(1)));
                continue;
            }
            String words = numberWords(line);
            if (!words.equals(line) && LyricsParser.parseHeading(words).isPresent()) {
                out.add(heading(words));
                continue;
            }
            out.add(line.strip().isEmpty() ? "" : line.strip());
        }
        // headings in one style ("Verse 1", "Chorus"); blank-line card breaks inside sections are kept
        return LyricsParser.format(LyricsParser.parse(String.join("\n", out)));
    }

    private static String heading(String label) {
        String l = numberWords(label.strip().replaceAll("\\s+", " "));
        return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1).toLowerCase(Locale.ROOT);
    }

    /** "Verse One" → "Verse 1" (only for short label-like lines). */
    private static String numberWords(String line) {
        String s = line.strip();
        if (s.length() > 24) {
            return line;
        }
        String[] parts = s.split("\\s+");
        if (parts.length == 2 && NUMBER_WORDS.containsKey(parts[1].toLowerCase(Locale.ROOT).replace(":", ""))) {
            return parts[0] + " " + NUMBER_WORDS.get(parts[1].toLowerCase(Locale.ROOT).replace(":", ""));
        }
        return line;
    }

    /** Re-splits every section into cards of at most {@code linesPerCard} lines (existing card breaks are replaced). */
    public static String splitIntoCards(String text, int linesPerCard) {
        int n = Math.max(1, linesPerCard);
        List<SongSection> sections = LyricsParser.parse(text);
        for (SongSection s : sections) {
            List<String> lines = new ArrayList<>();
            for (String l : (s.getContent() == null ? "" : s.getContent()).split("\n")) {
                if (!l.isBlank()) {
                    lines.add(l.strip());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append(i % n == 0 ? "\n\n" : "\n");
                }
                sb.append(lines.get(i));
            }
            s.setContent(sb.toString());
        }
        return LyricsParser.format(sections);
    }

    // ------------------------------------------------------------------ cards

    /** One presentation card while editing: its section and its lines. */
    public record Card(int section, String sectionLabel, int index, String text) {
        /** "Verse 1A", "Verse 1B", or "Chorus" for a one-card section. */
        public String name(int cardsInSection) {
            return cardsInSection <= 1 ? sectionLabel : sectionLabel + (char) ('A' + Math.min(index, 25));
        }
    }

    /** The cards of the lyrics text, in written order. */
    public static List<Card> cards(String text) {
        List<Card> out = new ArrayList<>();
        List<SongSection> sections = LyricsParser.parse(text);
        for (int s = 0; s < sections.size(); s++) {
            List<String> parts = SlideGenerator.splitOnBlankLines(sections.get(s).getContent());
            for (int i = 0; i < parts.size(); i++) {
                out.add(new Card(s, sections.get(s).getLabel(), i, parts.get(i)));
            }
        }
        return out;
    }

    /** Joins card {@code cardIndex} (global, written order) with the next card of the same section. */
    public static String mergeWithNext(String text, int cardIndex) {
        return edit(text, (cards, s, i) -> {
            if (i + 1 < cards.get(s).size()) {
                cards.get(s).set(i, cards.get(s).get(i) + "\n" + cards.get(s).remove(i + 1));
            }
        }, cardIndex);
    }

    /** Splits a card into two before its line {@code atLine} (1 = after the first line). */
    public static String split(String text, int cardIndex, int atLine) {
        return edit(text, (cards, s, i) -> {
            String[] lines = cards.get(s).get(i).split("\n");
            int at = Math.clamp(atLine, 1, Math.max(1, lines.length - 1));
            if (lines.length < 2) {
                return;
            }
            cards.get(s).set(i, String.join("\n", java.util.Arrays.copyOfRange(lines, 0, at)));
            cards.get(s).add(i + 1, String.join("\n", java.util.Arrays.copyOfRange(lines, at, lines.length)));
        }, cardIndex);
    }

    /** Moves a card one place up (-1) or down (+1) within its section. */
    public static String move(String text, int cardIndex, int delta) {
        return edit(text, (cards, s, i) -> {
            int j = i + delta;
            if (j >= 0 && j < cards.get(s).size()) {
                String c = cards.get(s).remove(i);
                cards.get(s).add(j, c);
            }
        }, cardIndex);
    }

    /** Replaces a card's text (an empty text removes the card). */
    public static String replace(String text, int cardIndex, String newText) {
        return edit(text, (cards, s, i) -> {
            if (newText == null || newText.isBlank()) {
                cards.get(s).remove(i);
            } else {
                cards.get(s).set(i, newText.strip());
            }
        }, cardIndex);
    }

    private interface CardEdit {
        void apply(List<List<String>> cards, int section, int index);
    }

    private static String edit(String text, CardEdit op, int cardIndex) {
        List<SongSection> sections = LyricsParser.parse(text);
        List<List<String>> cards = new ArrayList<>();
        int s = -1;
        int i = -1;
        int n = 0;
        for (int k = 0; k < sections.size(); k++) {
            List<String> parts = new ArrayList<>(SlideGenerator.splitOnBlankLines(sections.get(k).getContent()));
            for (int c = 0; c < parts.size(); c++, n++) {
                if (n == cardIndex) {
                    s = k;
                    i = c;
                }
            }
            cards.add(parts);
        }
        if (s < 0) {
            return text;
        }
        op.apply(cards, s, i);
        for (int k = 0; k < sections.size(); k++) {
            sections.get(k).setContent(String.join("\n\n", cards.get(k)));
        }
        return LyricsParser.format(sections.stream().filter(x -> x.getContent() != null && !x.getContent().isBlank()).toList());
    }

    // ------------------------------------------------------------------ timestamps

    /**
     * Gives cards the time their first line is sung in a timed lyrics file: card key → ms. Cards are
     * matched in order (a repeated chorus keeps the time it is first sung).
     *
     * @param cards card key → card text, in written order
     */
    public static Map<String, Long> cardTimes(Map<String, String> cards, List<TimedLyricsParser.Cue> cues) {
        Map<String, Long> out = new LinkedHashMap<>();
        int from = 0;
        for (Map.Entry<String, String> card : cards.entrySet()) {
            String first = norm(card.getValue().split("\n")[0]);
            if (first.isEmpty()) {
                continue;
            }
            int hit = find(cues, first, from);
            if (hit < 0) {
                hit = find(cues, first, 0);
            }
            if (hit >= 0) {
                out.put(card.getKey(), cues.get(hit).startMs());
                from = hit + 1;
            }
        }
        return out;
    }

    private static int find(List<TimedLyricsParser.Cue> cues, String first, int from) {
        for (int i = from; i < cues.size(); i++) {
            String c = norm(cues.get(i).text());
            if (!c.isEmpty() && (c.equals(first) || c.startsWith(first) || first.startsWith(c))) {
                return i;
            }
        }
        return -1;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
