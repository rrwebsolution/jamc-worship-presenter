package com.jamc.worship.service;

import com.jamc.worship.model.SectionType;
import com.jamc.worship.model.SongSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between plain lyrics text and song sections.
 *
 * <p>Recognised headings (case-insensitive, optional brackets and trailing colon):
 * {@code Verse 1}, {@code Chorus}, {@code Pre-Chorus}, {@code Bridge}, {@code Intro}, {@code Outro},
 * {@code Ending}, and any bracketed custom label such as {@code [Tag]}. Lyrics without any
 * headings are split on blank lines into Verse 1, Verse 2, ...
 *
 * <p>Blank lines inside a section are kept; they will mark slide breaks in the slide generator.
 */
public final class LyricsParser {

    private static final Pattern HEADING = Pattern.compile(
            "^\\s*\\[?\\s*([A-Za-z][A-Za-z _-]*?)\\s*(\\d+)?\\s*]?\\s*:?\\s*$");
    private static final Pattern BRACKETED = Pattern.compile("^\\s*\\[([^\\[\\]]{1,40})]\\s*$");
    private static final List<String> CUSTOM_KEYWORDS = List.of("tag", "interlude", "vamp", "instrumental", "coda");

    private LyricsParser() {
    }

    public static List<SongSection> parse(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        boolean hasHeadings = false;
        for (String line : lines) {
            if (parseHeading(line).isPresent()) {
                hasHeadings = true;
                break;
            }
        }
        return hasHeadings ? parseWithHeadings(lines) : parseParagraphs(lines);
    }

    /** Formats sections back into editable text that {@link #parse} reads back to the same sections. */
    public static String format(List<SongSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (SongSection section : sections) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(headingFor(section)).append('\n').append(section.getContent() == null ? "" : section.getContent().strip());
        }
        return sb.toString();
    }

    record Heading(SectionType type, String label, Integer number) {
    }

    static Optional<Heading> parseHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 48) {
            return Optional.empty();
        }
        Matcher m = HEADING.matcher(line);
        if (m.matches()) {
            String keyword = m.group(1).strip();
            Integer number = m.group(2) == null ? null : Integer.valueOf(m.group(2));
            Optional<SectionType> type = SectionType.fromKeyword(keyword);
            if (type.isPresent() && !isAmbiguousSingleLetter(keyword, line)) {
                return Optional.of(new Heading(type.get(), null, number));
            }
            if (CUSTOM_KEYWORDS.contains(keyword.toLowerCase(Locale.ROOT))) {
                String label = capitalize(keyword) + (number == null ? "" : " " + number);
                return Optional.of(new Heading(SectionType.CUSTOM, label, null));
            }
        }
        Matcher b = BRACKETED.matcher(line);
        if (b.matches()) {
            return Optional.of(new Heading(SectionType.CUSTOM, b.group(1).strip(), null));
        }
        return Optional.empty();
    }

    /** Single-letter shorthands ("V1", "C") only count as headings when numbered or bracketed. */
    private static boolean isAmbiguousSingleLetter(String keyword, String line) {
        return keyword.length() <= 2 && !line.matches(".*\\d.*") && !line.contains("[");
    }

    private static List<SongSection> parseWithHeadings(String[] lines) {
        List<SongSection> sections = new ArrayList<>();
        Map<SectionType, Integer> counters = new EnumMap<>(SectionType.class);
        SongSection current = null;
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            Optional<Heading> heading = parseHeading(line);
            if (heading.isPresent()) {
                flush(current, body, sections);
                current = newSection(heading.get(), counters);
                body.setLength(0);
            } else {
                if (current == null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    // Lyrics before the first heading become an automatically numbered verse.
                    current = newSection(new Heading(SectionType.VERSE, null, null), counters);
                }
                body.append(line.stripTrailing()).append('\n');
            }
        }
        flush(current, body, sections);
        return sections;
    }

    private static List<SongSection> parseParagraphs(String[] lines) {
        List<SongSection> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int verse = 0;
        for (int i = 0; i <= lines.length; i++) {
            boolean end = i == lines.length || lines[i].isBlank();
            if (!end) {
                body.append(lines[i].stripTrailing()).append('\n');
            } else if (!body.isEmpty()) {
                verse++;
                sections.add(new SongSection(SectionType.VERSE, "Verse " + verse, body.toString().strip()));
                body.setLength(0);
            }
        }
        return sections;
    }

    private static SongSection newSection(Heading heading, Map<SectionType, Integer> counters) {
        SectionType type = heading.type();
        if (type == SectionType.CUSTOM) {
            return new SongSection(type, heading.label(), "");
        }
        int seen = counters.merge(type, 1, Integer::sum);
        String label;
        if (heading.number() != null) {
            label = type.displayName() + " " + heading.number();
        } else if (type == SectionType.VERSE) {
            label = type.displayName() + " " + seen;
        } else {
            label = type.displayName();
        }
        return new SongSection(type, label, "");
    }

    private static void flush(SongSection current, StringBuilder body, List<SongSection> out) {
        if (current == null) {
            return;
        }
        current.setContent(collapseBlankLines(body.toString()).strip());
        out.add(current);
    }

    private static String collapseBlankLines(String s) {
        return s.replaceAll("\n{3,}", "\n\n");
    }

    private static String headingFor(SongSection section) {
        String label = section.getLabel() == null || section.getLabel().isBlank()
                ? section.getType().displayName() : section.getLabel().strip();
        if (section.getType() != SectionType.CUSTOM) {
            Optional<Heading> parsed = parseHeading(label);
            if (parsed.isPresent() && parsed.get().type() == section.getType()) {
                return label;
            }
        }
        return "[" + label + "]";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
