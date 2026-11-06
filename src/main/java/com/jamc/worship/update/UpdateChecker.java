package com.jamc.worship.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * New versions of the app come from the GitHub Releases page (the installer built by the release
 * workflow). Checking needs the internet; without it nothing happens and the app works as usual.
 * Nothing is installed without the operator's OK. Blocking: call off the Swing thread.
 */
public final class UpdateChecker {

    public static final String RELEASES_API = "https://api.github.com/repos/rrwebsolution/jamc-worship-presenter/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/rrwebsolution/jamc-worship-presenter/releases/latest";

    /** A published version and its Windows installer; {@code notes} is its "What's new" (may be empty). */
    public record Release(String version, String installerUrl, String installerName, long size, String notes) {
    }

    /** Settings → General: look for a new version when the app starts. */
    public static final String KEY_AUTO_CHECK = "update.autoCheck";
    /** A version the operator chose to skip ("Skip This Version"). */
    public static final String KEY_SKIPPED = "update.skippedVersion";

    /** This installed version, or null when running from the source code (development). */
    public static String currentVersion() {
        return UpdateChecker.class.getPackage().getImplementationVersion();
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The latest published release with an .exe installer; empty when there is none. */
    public Optional<Release> latest() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_API)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "LiveCat-Multimedia").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw new IOException("GitHub answered " + res.statusCode());
        }
        return parse(res.body());
    }

    static Optional<Release> parse(String json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        String tag = root.path("tag_name").asText("");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        for (JsonNode a : root.path("assets")) {
            String name = a.path("name").asText("");
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                return Optional.of(new Release(version, a.path("browser_download_url").asText(), name, a.path("size").asLong(),
                        WhatsNew.fromReleaseBody(root.path("body").asText(""))));
            }
        }
        return Optional.empty();
    }

    /** True when {@code candidate} (e.g. "0.1.2") is a later version than {@code current} ("0.1.1"). */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || current == null) {
            return false;
        }
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int number(String part) {
        String digits = part.replaceAll("\\D.*$", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Downloads the installer into {@code dir}; {@code progress} receives the bytes received so far. */
    public Path download(Release release, Path dir, LongConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path part = dir.resolve(release.installerName() + ".part");
        Path done = dir.resolve(release.installerName());
        HttpRequest req = HttpRequest.newBuilder(URI.create(release.installerUrl()))
                .header("User-Agent", "LiveCat-Multimedia").GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IOException("The download failed (" + res.statusCode() + ").");
        }
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                out.write(buf, 0, n);
                total += n;
                progress.accept(total);
            }
            if (release.size() > 0 && total != release.size()) {
                throw new IOException("The download was incomplete. Please try again.");
            }
        }
        return Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
    }
}
