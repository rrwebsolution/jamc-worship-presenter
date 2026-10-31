package com.jamc.worship.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads timed lyrics files (LRC, SRT, WebVTT) into normal song lyrics.
 *
 * <p>Lines are grouped into stanzas (a blank line, a pause in the timing or a long block starts a new
 * one). A stanza that comes back word for word is the chorus: it is kept once and repeated in the
 * section order. The time each section is first sung is kept for lyrics sync. The result is plain
 * lyrics text with headings, ready for the song editor.
 */
public final class TimedLyricsParser {

    public enum Format { LRC, SRT, VTT }

    /** One timed line. */
    public record Cue(long startMs, long endMs, String text) {
    }

    /**
     * @param text        lyrics with section headings ("Verse 1", "Chorus"...)
     * @param arrangement section labels in sung order ("Verse 1", "Chorus", "Verse 2", "Chorus")
     * @param sync        label → time (ms) the section is first sung
     */
    public record Result(String title, String artist, String text, List<String> arrangement, Map<String, Long> sync,
                         int lineCount, List<Cue> cues) {
    }

    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern LRC_TAG = Pattern.compile("^\\[(ti|ar|al|by|au|length|offset|re|ve):(.*)]\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUE_TIME = Pattern.compile(
            "((?:\\d{1,2}:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{1,2}[.,]\\d{1,3})");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>|\\{\\\\[^}]*}");
    /** A pause this long between lines starts a new stanza. */
    private static final long STANZA_GAP_MS = 4_500;
    private static final int MAX_STANZA_LINES = 8;

    private TimedLyricsParser() {
    }

    /** The format from the file name, else from the content. */
    public static Format detect(String fileName, String content) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".lrc")) {
            return Format.LRC;
        }
        if (n.endsWith(".vtt")) {
            return Format.VTT;
        }
        if (n.endsWith(".srt")) {
            return Format.SRT;
        }
        String c = content == null ? "" : content.stripLeading();
        if (c.startsWith("WEBVTT")) {
            return Format.VTT;
        }
        return CUE_TIME.matcher(c).find() ? Format.SRT : Format.LRC;
    }

    public static Result parse(String fileName, String content) {
        String text = content == null ? "" : content.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> tags = new LinkedHashMap<>();
        List<Cue> cues = switch (detect(fileName, text)) {
            case LRC -> parseLrc(text, tags);
            case SRT, VTT -> parseCues(text);
        };
        return toLyrics(cues, tags.get("ti"), tags.get("ar"));
    }

    static List<Cue> parseLrc(String text, Map<String, String> tags) {
        List<Cue> out = new ArrayList<>();
        long offset = 0;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            Matcher tag = LRC_TAG.matcher(line);
            if (tag.matches()) {
                String key = tag.group(1).toLowerCase(Locale.ROOT);
                tags.put(key, tag.group(2).strip());
                if (key.equals("offset")) {
                    try {
                        offset = Long.parseLong(tag.group(2).strip());
                    } catch (NumberFormatException ignored) {
                        // a broken offset tag is ignored
                    }
                }
                continue;
            }
            Matcher m = LRC_TIME.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000 + fraction(m.group(3));
                times.add(Math.max(0, ms - offset)); // a positive offset makes the lyrics appear sooner
                end = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String words = clean(line.substring(end).replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "")); // enhanced LRC word times
            for (long t : times) {
                out.add(new Cue(t, -1, words));
            }
        }
        out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        return out;
    }

    /** SRT and WebVTT: blocks of "start --> end" followed by text lines. */
    static List<Cue> parseCues(String text) {
        List<Cue> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CUE_TIME.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            long start = time(m.group(1));
            long end = time(m.group(2));
            StringBuilder words = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].isBlank() && !CUE_TIME.matcher(lines[j]).find()) {
                String l = clean(lines[j]);
                if (!l.isEmpty()) {
                    if (!words.isEmpty()) {
                        words.append('\n');
                    }
                    words.append(l);
                }
                j++;
            }
            i = j - 1;
            for (String l : words.toString().split("\n")) {
                // auto captions repeat the previous line while the next one appears: keep each line once
                if (!l.isBlank() && (out.isEmpty() || !out.getLast().text().equalsIgnoreCase(l))) {
                    out.add(new Cue(start, end, l));
                }
            }
        }
        return out;
    }

    private static Result toLyrics(List<Cue> cues, String title, String artist) {
        // 1) stanzas
        List<List<Cue>> stanzas = new ArrayList<>();
        List<Cue> current = new ArrayList<>();
        Cue previous = null;
        for (Cue c : cues) {
            boolean blank = c.text().isBlank() || isMusicMark(c.text());
            long gap = previous == null ? 0 : c.startMs() - (previous.endMs() > 0 ? previous.endMs() : previous.startMs());
            boolean pause = previous != null && gap > (previous.endMs() > 0 ? 1_500 : STANZA_GAP_MS);
            if ((blank || pause || current.size() >= MAX_STANZA_LINES) && !current.isEmpty()) {
                stanzas.add(current);
                current = new ArrayList<>();
            }
            if (!blank) {
                current.add(c);
            }
            previous = c;
        }
        if (!current.isEmpty()) {
            stanzas.add(current);
        }

        // 2) a stanza sung more than once is the chorus; keep each stanza once, in sung order
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (List<Cue> s : stanzas) {
            seen.merge(key(s), 1, Integer::sum);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Long> sync = new LinkedHashMap<>();
        List<String> arrangement = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int verses = 0;
        int choruses = 0;
        long repeatedKinds = seen.values().stream().filter(n -> n > 1).count();
        int lines = 0;
        for (List<Cue> s : stanzas) {
            String k = key(s);
            String label = labels.get(k);
            if (label == null) {
                boolean chorus = seen.get(k) > 1;
                label = chorus ? (repeatedKinds > 1 ? "Chorus " + (++choruses) : "Chorus") : "Verse " + (++verses);
                labels.put(k, label);
                sync.put(label, s.getFirst().startMs());
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(label).append('\n');
                for (Cue c : s) {
                    text.append(c.text()).append('\n');
                    lines++;
                }
                text.setLength(text.length() - 1);
            }
            arrangement.add(label);
        }
        return new Result(blankToNull(title), blankToNull(artist), text.toString(), arrangement, sync, lines, cues);
    }

    private static String key(List<Cue> stanza) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : stanza) {
            sb.append(c.text().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip()).append('|');
        }
        return sb.toString();
    }

    private static boolean isMusicMark(String text) {
        String t = text.strip();
        return t.matches("[♪♫\\s]*") || t.equalsIgnoreCase("[music]") || t.equalsIgnoreCase("(music)")
                || t.equalsIgnoreCase("[instrumental]") || t.equalsIgnoreCase("[applause]");
    }

    private static String clean(String s) {
        return TAGS.matcher(s).replaceAll("").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replaceAll("^[♪♫\\s]+|[♪♫\\s]+$", "").strip();
    }

    private static long fraction(String digits) {
        if (digits == null || digits.isEmpty()) {
            return 0;
        }
        return switch (digits.length()) {
            case 1 -> Long.parseLong(digits) * 100;
            case 2 -> Long.parseLong(digits) * 10;
            default -> Long.parseLong(digits.substring(0, 3));
        };
    }

    /** "01:02:03,450", "02:03.450" → ms. */
    private static long time(String s) {
        String[] parts = s.replace(',', '.').split(":");
        long h = 0;
        long m;
        String sec;
        if (parts.length == 3) {
            h = Long.parseLong(parts[0]);
            m = Long.parseLong(parts[1]);
            sec = parts[2];
        } else {
            m = Long.parseLong(parts[0]);
            sec = parts[1];
        }
        String[] sf = sec.split("\\.");
        return h * 3_600_000 + m * 60_000 + Long.parseLong(sf[0]) * 1000 + (sf.length > 1 ? fraction(sf[1]) : 0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
