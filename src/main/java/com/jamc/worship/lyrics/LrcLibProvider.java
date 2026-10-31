package com.jamc.worship.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * LRCLIB (lrclib.net): a free public lyrics API with plain and line-synced (LRC) lyrics, used through its
 * documented API only. Its lyrics are contributed by its community, so it is OFF until the operator turns it on,
 * and the church remains responsible for being licensed to project a song (e.g. CCLI).
 */
public final class LrcLibProvider implements LyricsProvider {

    public static final String ID = "lrclib";
    public static final String KEY_ENABLED = "lyrics.lrclib.enabled";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BooleanSupplier enabled;
    private final String userAgent;

    public LrcLibProvider(BooleanSupplier enabled, String userAgent) {
        this.enabled = enabled;
        this.userAgent = userAgent;
    }

    @Override public String id() { return ID; }
    @Override public String name() { return "LRCLIB (synced lyrics)"; }
    @Override public String description() {
        return "Public synced-lyrics API (lrclib.net). Community-contributed: your church must be licensed to project each song.";
    }
    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SEARCH, Capability.PLAIN_LYRICS, Capability.SYNCED_LYRICS, Capability.METADATA,
                Capability.STORE_OFFLINE);
    }
    @Override public Status status() { return enabled.getAsBoolean() ? Status.CONNECTED : Status.DISABLED; }
    @Override public Trust trust() { return Trust.COMMUNITY; }

    @Override
    public List<LyricsResult> search(String query) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://lrclib.net/api/search?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
        HttpResponse<String> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (IOException e) {
            throw new IOException("LRCLIB could not be reached (no internet?).", e);
        }
        if (r.statusCode() == 429 || r.statusCode() == 503) {
            throw new IOException("LRCLIB is busy right now. Try again in a moment.");
        }
        if (r.statusCode() != 200) {
            throw new IOException("LRCLIB answered " + r.statusCode() + ".");
        }
        return parse(query, r.body());
    }

    /** LRCLIB search JSON → results (lyrics included, so no second request is needed). */
    List<LyricsResult> parse(String query, String body) throws IOException {
        List<LyricsResult> out = new ArrayList<>();
        for (JsonNode n : JSON.readTree(body)) {
            if (n.path("instrumental").asBoolean(false)) {
                continue;
            }
            String plain = text(n, "plainLyrics");
            String synced = text(n, "syncedLyrics");
            if (plain == null && synced == null) {
                continue;
            }
            String title = n.path("trackName").asText("");
            String artist = n.path("artistName").asText("");
            double dur = n.path("duration").asDouble(0);
            out.add(new LyricsResult(ID, name(), trust(), "lrclib:" + n.path("id").asText(), title, artist,
                    dur > 0 ? Math.round(dur * 1000) : null, plain, synced, null, LyricsProviders.score(query, title, artist)));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        String s = n.path(field).isTextual() ? n.path(field).asText() : null;
        return s == null || s.isBlank() ? null : s;
    }

    @Override
    public LyricsResult fetch(LyricsResult result) {
        return result; // search results already carry the lyrics
    }
}
