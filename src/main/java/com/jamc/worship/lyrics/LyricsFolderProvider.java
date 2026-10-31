package com.jamc.worship.lyrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The church's own lyrics files: a folder of TXT, LRC, SRT or VTT files (for example exported from a
 * licensed service such as CCLI SongSelect). File names like "Bethel Music - Goodness of God.lrc" or
 * LRC [ti:] / [ar:] tags give the title and artist. Works offline.
 */
public final class LyricsFolderProvider implements LyricsProvider {

    public static final String ID = "folder";
    public static final String KEY_FOLDER = "lyrics.folder";
    private static final Set<String> EXTENSIONS = Set.of("txt", "lrc", "srt", "vtt");
    private static final Pattern TAG = Pattern.compile("^\\[(ti|ar):(.*)]\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final Supplier<String> folder;

    public LyricsFolderProvider(Supplier<String> folder) {
        this.folder = folder;
    }

    @Override public String id() { return ID; }
    @Override public String name() { return "Lyrics Folder"; }
    @Override public String description() {
        Path dir = dir();
        return "The church's own TXT / LRC / SRT / VTT files" + (dir == null ? "." : ": " + dir);
    }
    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SEARCH, Capability.PLAIN_LYRICS, Capability.SYNCED_LYRICS, Capability.METADATA,
                Capability.STORE_OFFLINE);
    }
    @Override public Status status() {
        return dir() == null ? Status.NOT_CONFIGURED : Status.CONNECTED;
    }
    @Override public Trust trust() { return Trust.CHURCH; }

    private Path dir() {
        String f = folder.get();
        if (f == null || f.isBlank()) {
            return null;
        }
        Path p = Path.of(f);
        return Files.isDirectory(p) ? p : null;
    }

    @Override
    public List<LyricsResult> search(String query) throws IOException {
        Path dir = dir();
        if (dir == null) {
            return List.of();
        }
        List<LyricsResult> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 4)) {
            for (Path f : files.filter(Files::isRegularFile).filter(LyricsFolderProvider::isLyricsFile).toList()) {
                String[] ta = titleArtist(f);
                double score = LyricsProviders.score(query, ta[0], ta[1]);
                if (score > 0) {
                    // local files are small: read now, so SYNCED / PLAIN and the preview are right away
                    out.add(fetch(new LyricsResult(ID, name(), trust(), f.toString(), ta[0], ta[1], null, null, null, null, score)));
                }
            }
        }
        return out;
    }

    @Override
    public LyricsResult fetch(LyricsResult r) throws IOException {
        Path f = Path.of(r.reference());
        String text = Files.readString(f, StandardCharsets.UTF_8);
        return ext(f).equals("txt") ? r.withLyrics(text, null) : r.withLyrics(null, text);
    }

    static boolean isLyricsFile(Path f) {
        return EXTENSIONS.contains(ext(f));
    }

    private static String ext(Path f) {
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Title and artist from LRC tags, else from "Artist - Title" in the file name. */
    static String[] titleArtist(Path f) {
        String base = f.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String title = base;
        String artist = "";
        int dash = base.indexOf(" - ");
        if (dash > 0) {
            artist = base.substring(0, dash).strip();
            title = base.substring(dash + 3).strip();
        }
        if (ext(f).equals("lrc")) {
            try {
                String head = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = TAG.matcher(head.length() > 2000 ? head.substring(0, 2000) : head);
                while (m.find()) {
                    if (m.group(1).equalsIgnoreCase("ti") && !m.group(2).isBlank()) {
                        title = m.group(2).strip();
                    } else if (m.group(1).equalsIgnoreCase("ar") && !m.group(2).isBlank()) {
                        artist = m.group(2).strip();
                    }
                }
            } catch (IOException ignored) {
                // the file name is enough
            }
        }
        return new String[]{title, artist};
    }
}
