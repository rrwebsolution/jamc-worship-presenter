package com.jamc.worship.lyrics;

import com.jamc.worship.model.Song;
import com.jamc.worship.model.SongQuery;
import com.jamc.worship.service.LyricsParser;
import com.jamc.worship.service.SongService;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Songs already saved in this computer's library (works offline). Found songs are opened, never overwritten. */
public final class LocalLibraryProvider implements LyricsProvider {

    public static final String ID = "local";
    private final SongService songs;

    public LocalLibraryProvider(SongService songs) {
        this.songs = songs;
    }

    @Override public String id() { return ID; }
    @Override public String name() { return "Local Library"; }
    @Override public String description() { return "Lyrics already saved in LiveCat Multimedia. Works offline."; }
    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SEARCH, Capability.PLAIN_LYRICS, Capability.METADATA, Capability.STORE_OFFLINE);
    }
    @Override public Status status() { return Status.CONNECTED; }
    @Override public Trust trust() { return Trust.CHURCH; }

    @Override
    public List<LyricsResult> search(String query) {
        return songs.search(new SongQuery(query, false, null)).stream().limit(20)
                .map(s -> new LyricsResult(ID, name(), trust(), String.valueOf(s.getId()), s.getTitle(), s.getAuthor(), null,
                        null, null, s.getId(), LyricsProviders.score(query, s.getTitle(), s.getAuthor())))
                .toList();
    }

    @Override
    public LyricsResult fetch(LyricsResult r) {
        Song s = r.existingSongId() == null ? null : songs.load(r.existingSongId()).orElse(null);
        return s == null ? r : r.withLyrics(LyricsParser.format(s.getSections()), null);
    }
}
