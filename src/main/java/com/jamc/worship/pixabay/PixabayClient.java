package com.jamc.worship.pixabay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The official Pixabay Videos API ({@code https://pixabay.com/api/videos/}) only; Pixabay's web pages are never read.
 *
 * <ul>
 *   <li>The church's own API key (settings {@value #KEY_API_KEY}); it is never written to the log or shown again.</li>
 *   <li>Every answer is kept for 24 hours (in memory and on disk), as the API documentation requires, so browsing the
 *       same preset again sends no request.</li>
 *   <li>The rate limit (100 requests a minute per key) is followed from the X-RateLimit headers; when it is used up no
 *       request is sent until the window resets.</li>
 *   <li>Safe search is always on (church use).</li>
 * </ul>
 * Calls block: run them off the Swing thread.
 */
public final class PixabayClient {

    private static final Logger log = LoggerFactory.getLogger(PixabayClient.class);

    public static final String KEY_API_KEY = "pixabay.apiKey";
    static final String ENDPOINT = "https://pixabay.com/api/videos/";
    static final Duration CACHE_TTL = Duration.ofHours(24);
    public static final int PER_PAGE = 24;

    /** Why a request failed, in words an operator can act on. */
    public enum Problem {
        NO_KEY("No Pixabay API key yet. Add the church's free key in Settings → Integrations → Pixabay."),
        INVALID_KEY("Pixabay did not accept the API key. Check it in Settings → Integrations → Pixabay."),
        RATE_LIMIT("Too many Pixabay searches in one minute. Wait a moment and try again."),
        OFFLINE("Pixabay cannot be reached. Check the internet connection."),
        TIMEOUT("Pixabay took too long to answer. Check the internet connection and try again."),
        NOT_FOUND("That video is no longer on Pixabay."),
        SERVER("Pixabay had a problem answering. Try again in a moment.");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public static final class PixabayException extends IOException {
        private final Problem problem;

        public PixabayException(Problem problem, String detail) {
            super(detail == null || detail.isBlank() ? problem.message() : problem.message() + " (" + detail + ")");
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** One page of results. {@code totalHits} is how many the API lets us page through (at most 500). */
    public record Page(List<PixabayVideo> videos, int totalHits, int page) {
        public boolean hasMore() {
            return page * PER_PAGE < totalHits;
        }
    }

    /** One HTTP answer (status, body, headers), so tests can stand in for the network. */
    public record Response(int status, String body, Map<String, List<String>> headers) {
        String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    public interface Fetcher {
        Response get(String url) throws IOException, InterruptedException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private record Cached(String body, long at) {
    }

    private final Supplier<String> apiKey;
    private final Path cacheDir;
    private final Fetcher fetcher;
    private final Map<String, Cached> memory = new ConcurrentHashMap<>();
    private volatile long blockedUntil;
    /** Test hook: the clock in ms. */
    private final Supplier<Long> clock;

    public PixabayClient(Supplier<String> apiKey, Path cacheDir) {
        this(apiKey, cacheDir, PixabayClient::httpGet, System::currentTimeMillis);
    }

    PixabayClient(Supplier<String> apiKey, Path cacheDir, Fetcher fetcher, Supplier<Long> clock) {
        this.apiKey = apiKey;
        this.cacheDir = cacheDir;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public boolean hasApiKey() {
        String k = apiKey.get();
        return k != null && !k.isBlank();
    }

    /** One page (1-based) of safe-search results for {@code query}. */
    public Page search(String query, int page) throws IOException {
        String q = query == null ? "" : query.strip();
        if (q.length() > 100) {
            q = q.substring(0, 100); // the API's limit
        }
        int p = Math.max(1, page);
        String params = "q=" + enc(q) + "&safesearch=true&order=popular&per_page=" + PER_PAGE + "&page=" + p;
        JsonNode root = request(params);
        List<PixabayVideo> out = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            PixabayVideo v = parse(hit);
            if (v.playback() != null) {
                out.add(v);
            }
        }
        return new Page(out, root.path("totalHits").asInt(out.size()), p);
    }

    /** The video with this id, with fresh addresses, or empty when Pixabay no longer has it. */
    public Optional<PixabayVideo> byId(long id) throws IOException {
        JsonNode root = request("id=" + id);
        for (JsonNode hit : root.path("hits")) {
            return Optional.of(parse(hit));
        }
        return Optional.empty();
    }

    /** Checks a key with one small request (never cached). Throws with the reason when it does not work. */
    public void validate(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new PixabayException(Problem.NO_KEY, null);
        }
        send(ENDPOINT + "?key=" + enc(key.strip()) + "&q=sky&per_page=3", key.strip());
    }

    // ------------------------------------------------------------------ internals

    private JsonNode request(String params) throws IOException {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new PixabayException(Problem.NO_KEY, null);
        }
        long now = clock.get();
        String cacheKey = sha(params);
        Cached c = memory.get(cacheKey);
        if (c == null) {
            c = readDisk(cacheKey);
        }
        if (c != null && now - c.at < CACHE_TTL.toMillis()) {
            return JSON.readTree(c.body);
        }
        if (now < blockedUntil) {
            throw new PixabayException(Problem.RATE_LIMIT, "about " + Math.max(1, (blockedUntil - now) / 1000) + " s");
        }
        String body = send(ENDPOINT + "?key=" + enc(key.strip()) + "&" + params, key.strip());
        Cached fresh = new Cached(body, now);
        memory.put(cacheKey, fresh);
        writeDisk(cacheKey, fresh);
        return JSON.readTree(body);
    }

    /** Sends one request; maps every failure to a {@link Problem}. The key never reaches a message or the log. */
    private String send(String url, String key) throws IOException {
        Response r;
        try {
            r = fetcher.get(url);
        } catch (HttpTimeoutException e) {
            throw new PixabayException(Problem.TIMEOUT, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PixabayException(Problem.TIMEOUT, null);
        } catch (IOException e) {
            log.info("Pixabay unreachable: {}", e.getClass().getSimpleName());
            throw new PixabayException(Problem.OFFLINE, null);
        }
        followRateLimit(r);
        String body = r.body() == null ? "" : r.body().replace(key, "***");
        if (r.status() == 200) {
            return body;
        }
        log.info("Pixabay answered HTTP {}: {}", r.status(), body.length() > 160 ? body.substring(0, 160) : body);
        Problem p = switch (r.status()) {
            case 429 -> Problem.RATE_LIMIT;
            case 401, 403 -> Problem.INVALID_KEY;
            case 400 -> body.toLowerCase(java.util.Locale.ROOT).contains("key") ? Problem.INVALID_KEY : Problem.SERVER;
            case 404 -> Problem.NOT_FOUND;
            default -> Problem.SERVER;
        };
        throw new PixabayException(p, p == Problem.SERVER ? "HTTP " + r.status() : null);
    }

    private void followRateLimit(Response r) {
        String remaining = r.header("X-RateLimit-Remaining");
        String reset = r.header("X-RateLimit-Reset");
        try {
            if (r.status() == 429 || (remaining != null && Integer.parseInt(remaining.strip()) <= 0)) {
                long seconds = reset == null ? 60 : Math.max(1, Long.parseLong(reset.strip()));
                blockedUntil = clock.get() + seconds * 1000;
            }
        } catch (NumberFormatException e) {
            // unknown header form: nothing to follow
        }
    }

    static PixabayVideo parse(JsonNode hit) {
        JsonNode v = hit.path("videos");
        return new PixabayVideo(hit.path("id").asLong(), text(hit, "pageURL"), text(hit, "tags"),
                hit.path("duration").asInt(0), text(hit, "user"), hit.path("user_id").asLong(),
                rendition(v.path("large")), rendition(v.path("medium")), rendition(v.path("small")), rendition(v.path("tiny")));
    }

    private static PixabayVideo.Rendition rendition(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        return new PixabayVideo.Rendition(text(n, "url"), n.path("width").asInt(), n.path("height").asInt(),
                n.path("size").asLong(), text(n, "thumbnail"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode f = n.path(field);
        return f.isMissingNode() || f.isNull() ? null : f.asText();
    }

    private Cached readDisk(String cacheKey) {
        if (cacheDir == null) {
            return null;
        }
        Path f = cacheDir.resolve(cacheKey + ".json");
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            Cached c = new Cached(Files.readString(f), Files.getLastModifiedTime(f).toMillis());
            memory.put(cacheKey, c);
            return c;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDisk(String cacheKey, Cached c) {
        if (cacheDir == null) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            Path f = cacheDir.resolve(cacheKey + ".json");
            Files.writeString(f, c.body);
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(c.at));
            pruneDisk();
        } catch (IOException e) {
            log.debug("Could not cache a Pixabay answer", e);
        }
    }

    /** Answers older than 24 hours are deleted from the disk cache. */
    private void pruneDisk() throws IOException {
        long limit = clock.get() - CACHE_TTL.toMillis();
        try (var files = Files.list(cacheDir)) {
            for (Path f : files.toList()) {
                if (Files.getLastModifiedTime(f).toMillis() < limit) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    private static Response httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET()
                .header("Accept", "application/json").build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body(), r.headers().map());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
