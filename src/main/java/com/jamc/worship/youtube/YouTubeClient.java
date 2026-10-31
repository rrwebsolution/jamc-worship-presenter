package com.jamc.worship.youtube;

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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * YouTube metadata through YouTube's own public APIs only (no page scraping):
 * <ul>
 *   <li><b>Search</b> uses the YouTube Data API v3 with the church's own API key (Google Cloud
 *       console, "YouTube Data API v3"), stored in settings as {@value #KEY_API_KEY}. Results are
 *       limited to videos whose owners allow embedding.</li>
 *   <li><b>Paste a link</b> works without a key through YouTube's oEmbed endpoint.</li>
 *   <li><b>Availability</b> (ONLINE / OFFLINE / UNAVAILABLE) is checked through oEmbed too.</li>
 * </ul>
 * Captions or lyrics are never downloaded here: YouTube only lets a video's owner download its captions.
 */
public final class YouTubeClient {

    public static final String KEY_API_KEY = "youtube.apiKey";

    /** Whether a YouTube video can be played now. */
    public enum Availability {
        /** Internet works and YouTube serves the video. */
        ONLINE,
        /** No internet connection (or YouTube unreachable). */
        OFFLINE,
        /** YouTube answered, but the video is private, removed or not embeddable. */
        UNAVAILABLE,
        /** Not checked yet. */
        UNKNOWN;

        public String label() {
            return switch (this) {
                case ONLINE -> "ONLINE";
                case OFFLINE -> "OFFLINE";
                case UNAVAILABLE -> "UNAVAILABLE";
                case UNKNOWN -> "CHECKING…";
            };
        }
    }

    /** A failure the operator can act on (no key, quota used up, no internet...). */
    public static final class YouTubeException extends IOException {
        public YouTubeException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final long AVAILABILITY_TTL_MS = 5 * 60_000;

    private final Supplier<String> apiKey;
    private final Map<String, Checked> availability = new ConcurrentHashMap<>();

    private record Checked(Availability value, long at) {
    }

    public YouTubeClient(Supplier<String> apiKey) {
        this.apiKey = apiKey;
    }

    public boolean hasApiKey() {
        String k = apiKey.get();
        return k != null && !k.isBlank();
    }

    /** Embeddable videos matching the words, best match first, with durations. */
    public List<YouTubeVideo> search(String query, int max) throws IOException {
        if (!hasApiKey()) {
            throw new YouTubeException("Searching YouTube needs the church's YouTube Data API key (YouTube API Key…).\n"
                    + "You can still paste a YouTube link into the search box.");
        }
        String url = API + "search?part=snippet&type=video&videoEmbeddable=true&safeSearch=moderate&maxResults="
                + Math.clamp(max, 1, 25) + "&q=" + enc(query) + "&key=" + enc(apiKey.get().strip());
        JsonNode root = getJson(url);
        Map<String, YouTubeVideo> found = new LinkedHashMap<>();
        for (JsonNode item : root.path("items")) {
            String id = item.path("id").path("videoId").asText("");
            if (!YouTubeRef.isVideoId(id)) {
                continue;
            }
            JsonNode sn = item.path("snippet");
            found.put(id, new YouTubeVideo(id, unescape(sn.path("title").asText(id)), unescape(sn.path("channelTitle").asText("")),
                    null, thumbnail(sn, id)));
        }
        if (found.isEmpty()) {
            return List.of();
        }
        // durations come from a second (cheap) call
        Map<String, Long> durations = durations(found.keySet());
        List<YouTubeVideo> out = new ArrayList<>();
        for (YouTubeVideo v : found.values()) {
            out.add(new YouTubeVideo(v.id(), v.title(), v.channel(), durations.get(v.id()), v.thumbnailUrl()));
        }
        return out;
    }

    /** Details of one video from a pasted link or id: the Data API when there is a key, else oEmbed. */
    public Optional<YouTubeVideo> lookup(String linkOrId) throws IOException {
        Optional<String> id = YouTubeRef.parse(linkOrId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String vid = id.get();
        if (hasApiKey()) {
            JsonNode root = getJson(API + "videos?part=snippet,contentDetails,status&id=" + vid + "&key=" + enc(apiKey.get().strip()));
            JsonNode item = root.path("items").path(0);
            if (item.isMissingNode()) {
                throw new YouTubeException("This video is private, removed or not available.");
            }
            if (!item.path("status").path("embeddable").asBoolean(true)) {
                throw new YouTubeException("The owner of this video does not allow it to be played outside YouTube.");
            }
            JsonNode sn = item.path("snippet");
            return Optional.of(new YouTubeVideo(vid, sn.path("title").asText(vid), sn.path("channelTitle").asText(""),
                    parseDuration(item.path("contentDetails").path("duration").asText(null)), thumbnail(sn, vid)));
        }
        HttpResponse<String> r = send(oembed(vid));
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new YouTubeException("The owner of this video does not allow it to be played outside YouTube.");
        }
        if (r.statusCode() == 404 || r.statusCode() == 400) {
            throw new YouTubeException("This video is private, removed or not available.");
        }
        if (r.statusCode() != 200) {
            throw new YouTubeException("YouTube answered " + r.statusCode() + ".");
        }
        JsonNode n = JSON.readTree(r.body());
        String thumb = n.path("thumbnail_url").asText(YouTubeVideo.defaultThumbnail(vid));
        return Optional.of(new YouTubeVideo(vid, n.path("title").asText(vid), n.path("author_name").asText(""), null, thumb));
    }

    /** The last known availability (UNKNOWN until {@link #checkAvailability} ran). Never blocks. */
    public Availability cachedAvailability(String videoId) {
        Checked c = availability.get(videoId);
        return c == null ? Availability.UNKNOWN : c.value();
    }

    /** Whether the cached availability is missing or old. */
    public boolean needsCheck(String videoId) {
        Checked c = availability.get(videoId);
        return c == null || System.currentTimeMillis() - c.at() > AVAILABILITY_TTL_MS;
    }

    /** Asks YouTube (oEmbed) whether the video can be played now. Blocks; call off the event thread. */
    public Availability checkAvailability(String videoId) {
        Availability a;
        try {
            int code = send(oembed(videoId)).statusCode();
            a = code == 200 ? Availability.ONLINE : code >= 500 ? Availability.OFFLINE : Availability.UNAVAILABLE;
        } catch (IOException e) {
            a = Availability.OFFLINE;
        } catch (RuntimeException e) {
            a = Availability.OFFLINE;
        }
        availability.put(videoId, new Checked(a, System.currentTimeMillis()));
        return a;
    }

    // ------------------------------------------------------------------ internals

    private Map<String, Long> durations(Iterable<String> ids) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            JsonNode root = getJson(API + "videos?part=contentDetails&id=" + enc(String.join(",", ids)) + "&key="
                    + enc(apiKey.get().strip()));
            for (JsonNode item : root.path("items")) {
                Long d = parseDuration(item.path("contentDetails").path("duration").asText(null));
                if (d != null) {
                    out.put(item.path("id").asText(), d);
                }
            }
        } catch (IOException e) {
            // durations are a nicety: results still show without them
        }
        return out;
    }

    /** ISO 8601 durations as YouTube sends them ("PT4M13S", "P0D" for live streams). */
    static Long parseDuration(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            long ms = Duration.parse(iso).toMillis();
            return ms > 0 ? ms : null; // live streams report P0D
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String thumbnail(JsonNode snippet, String id) {
        JsonNode t = snippet.path("thumbnails");
        for (String size : new String[]{"high", "medium", "default"}) {
            String url = t.path(size).path("url").asText(null);
            if (url != null) {
                return url;
            }
        }
        return YouTubeVideo.defaultThumbnail(id);
    }

    private static String oembed(String id) {
        return "https://www.youtube.com/oembed?format=json&url=" + enc(YouTubeRef.watchUrl(id));
    }

    private JsonNode getJson(String url) throws IOException {
        HttpResponse<String> r = send(url);
        JsonNode root = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        if (r.statusCode() != 200) {
            String reason = root.path("error").path("errors").path(0).path("reason").asText("");
            String message = root.path("error").path("message").asText("YouTube answered " + r.statusCode());
            throw new YouTubeException(switch (reason) {
                case "quotaExceeded", "dailyLimitExceeded" -> "The YouTube API key has used up today's search quota. "
                        + "Paste a YouTube link instead, or try again tomorrow.";
                case "keyInvalid", "badRequest" -> "The YouTube API key is not valid. Check it in YouTube API Key….";
                case "accessNotConfigured", "forbidden" -> "\"YouTube Data API v3\" is not enabled for this API key "
                        + "in the Google Cloud console.";
                default -> message;
            });
        }
        return root;
    }

    private static HttpResponse<String> send(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET()
                .header("Accept", "application/json").build();
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (IOException e) {
            throw new YouTubeException("YouTube could not be reached. Check the internet connection.");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Search titles arrive HTML-escaped ("Goodness of God &amp; ..."). */
    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
