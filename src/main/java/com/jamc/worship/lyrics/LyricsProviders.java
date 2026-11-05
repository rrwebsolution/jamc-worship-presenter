package com.jamc.worship.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lyrics providers this app knows, and searching them: one source, or all configured sources with
 * fallback (a provider with no result, no internet or a busy server never stops the others), merged and
 * ranked:
 * <ol>
 *   <li>songs already in the local library first (they are opened, never overwritten);</li>
 *   <li>licensed / authorized lyrics;</li>
 *   <li>a confident title + artist match;</li>
 *   <li>lyrics with timestamps before plain lyrics.</li>
 * </ol>
 */
public final class LyricsProviders {

    /** What a search found, and why some sources found nothing (shown as notes, not errors). */
    public record Outcome(List<LyricsResult> results, List<String> notes, List<String> searched) {
    }

    private final List<LyricsProvider> all;

    public LyricsProviders(List<LyricsProvider> providers) {
        this.all = List.copyOf(providers);
    }

    /**
     * The providers this app ships: the local library, the church's lyrics folder and LRCLIB (off until
     * turned on in Settings → Integrations → Lyrics). A licensed worship lyrics service is added here.
     */
    public static LyricsProviders create(com.jamc.worship.config.AppConfig config, com.jamc.worship.service.SongService songs) {
        return new LyricsProviders(List.of(
                new LocalLibraryProvider(songs),
                new LyricsFolderProvider(() -> config.get(LyricsFolderProvider.KEY_FOLDER, null)),
                new LrcLibProvider(() -> config.getBoolean(LrcLibProvider.KEY_ENABLED, false),
                        "LiveCat Multimedia (church presentation software)")));
    }

    public List<LyricsProvider> all() {
        return all;
    }

    /** Providers that are set up and may be used (offered in the Source list). */
    public List<LyricsProvider> configured() {
        return all.stream().filter(p -> p.status() == LyricsProvider.Status.CONNECTED).toList();
    }

    public LyricsProvider byId(String id) {
        return all.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** Searches one provider ({@code providerId}), or every configured provider when it is null. Blocking. */
    public Outcome search(String query, String providerId) {
        List<LyricsProvider> targets = providerId == null ? configured()
                : configured().stream().filter(p -> p.id().equals(providerId)).toList();
        List<LyricsResult> found = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        for (LyricsProvider p : targets) {
            if (!p.capabilities().contains(LyricsProvider.Capability.SEARCH)) {
                continue;
            }
            searched.add(p.name());
            try {
                List<LyricsResult> r = p.search(query);
                if (r.isEmpty()) {
                    notes.add(p.name() + ": no results");
                }
                found.addAll(r);
            } catch (Exception e) {
                notes.add(p.name() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return new Outcome(rank(found), notes, searched);
    }

    /** Merges the same song from several providers (keeping the best copy) and orders the results. */
    static List<LyricsResult> rank(List<LyricsResult> found) {
        Comparator<LyricsResult> best = Comparator
                .comparing((LyricsResult r) -> r.existingSongId() == null)          // local library first
                .thenComparing(r -> r.trust() != LyricsProvider.Trust.LICENSED)      // licensed lyrics
                .thenComparing(r -> r.score() < 0.75)                                // confident match
                .thenComparing(r -> !r.hasTimed())                                   // timed before plain
                .thenComparing(Comparator.comparingDouble(LyricsResult::score).reversed());
        Map<String, LyricsResult> merged = new LinkedHashMap<>();
        for (LyricsResult r : found) {
            if (r.score() < 0.25) {
                continue; // not the song searched for
            }
            String key = (r.existingSongId() != null ? "local:" + r.existingSongId() : r.matchKey());
            merged.merge(key, r, (a, b) -> best.compare(a, b) <= 0 ? a : b);
        }
        return merged.values().stream().sorted(best).limit(40).toList();
    }

    /**
     * How well a title + artist match the search words (0..1): the share of the search words found, with a
     * bonus when every word of the title was searched for.
     */
    public static double score(String query, String title, String artist) {
        Set<String> q = words(query);
        if (q.isEmpty()) {
            return 0;
        }
        Set<String> t = words(title);
        Set<String> all = words((title == null ? "" : title) + " " + (artist == null ? "" : artist));
        long hit = q.stream().filter(all::contains).count();
        double s = hit / (double) q.size();
        if (!t.isEmpty() && q.containsAll(t)) {
            s = Math.min(1.0, s + 0.25);
        }
        return s;
    }

    private static Set<String> words(String s) {
        if (s == null) {
            return Set.of();
        }
        return Arrays.stream(s.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", " ").split("[^\\p{L}\\p{N}]+"))
                .filter(w -> !w.isBlank() && !w.equals("the") && !w.equals("a") && !w.equals("of") && !w.equals("feat"))
                .collect(Collectors.toSet());
    }
}
