package com.jamc.worship.youtube;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.stream.FFmpeg;
import com.jamc.worship.stream.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves a YouTube video on this computer as an .mp4 (data/youtube/videos/&lt;id&gt;.mp4) with yt-dlp. yt-dlp.exe is
 * fetched from its GitHub releases into tools/ the first time; when it stops working (YouTube changes often) it is
 * updated once and the download tried again. With FFmpeg installed the best picture up to 1080p is joined with its
 * sound; without FFmpeg only YouTube's single-file version is available (usually 360p or 720p).
 * Blocking: call from a background thread.
 */
public final class YouTubeDownloader {

    private static final Logger log = LoggerFactory.getLogger(YouTubeDownloader.class);
    private static final String YT_DLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final Pattern PERCENT = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");
    private static final String RESULT = "JAMC|";

    /** The saved file and what yt-dlp knows of it (null when unknown). */
    public record Result(Path file, Integer width, Integer height, Long durationMs) {
    }

    private final AppConfig config;

    public YouTubeDownloader(AppConfig config) {
        this.config = config;
    }

    /** The same folder as {@code MediaService.youTubeDownloadsDir()}. */
    public Path folder() {
        return config.paths().dataDir().resolve("youtube").resolve("videos");
    }

    /** The saved copy of this video, if it was downloaded before. */
    public Path existing(String videoId) {
        Path dir = folder();
        if (!YouTubeRef.isVideoId(videoId) || !Files.isDirectory(dir)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, videoId + ".*")) {
            for (Path p : ds) {
                String ext = p.getFileName().toString().substring(videoId.length() + 1);
                // "ID.mp4" only: not the halves of a join ("ID.f137.mp4") or an unfinished "ID.mp4.part"
                if (Files.isRegularFile(p) && !ext.contains(".")) {
                    return p;
                }
            }
        } catch (IOException e) {
            log.debug("Could not look in {}", dir, e);
        }
        return null;
    }

    /**
     * Downloads the video. {@code status} hears short progress lines ("Getting the downloader…", "Video 45%").
     *
     * @throws IOException with a message for the operator when it could not be downloaded
     */
    public Result download(String videoId, Consumer<String> status) throws IOException {
        if (!YouTubeRef.isVideoId(videoId)) {
            throw new IOException("That is not a YouTube video.");
        }
        Path exe = ensureYtDlp(status);
        Files.createDirectories(folder());
        try {
            return run(exe, videoId, status);
        } catch (IOException first) {
            log.warn("yt-dlp failed ({}); updating it and trying again", first.getMessage());
            status.accept("Updating the downloader…");
            runQuiet(exe, "-U");
            return run(exe, videoId, status);
        }
    }

    private Result run(Path exe, String videoId, Consumer<String> status) throws IOException {
        Path ffmpeg = FFmpeg.locate(config.get(StreamManager.KEY_FFMPEG_PATH, null));
        List<String> cmd = new ArrayList<>(List.of(exe.toString(),
                "--no-playlist", "--no-simulate", "--progress", "--newline", "--no-mtime",
                "-o", folder().resolve("%(id)s.%(ext)s").toString(),
                "--print", "after_move:" + RESULT + "%(filepath)s|%(width)s|%(height)s|%(duration)s"));
        if (ffmpeg != null) {
            cmd.addAll(List.of("--ffmpeg-location", ffmpeg.toString(),
                    "-f", "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=1080]+ba/b[height<=1080]/b",
                    "--merge-output-format", "mp4"));
        } else {
            cmd.addAll(List.of("-f", "b[ext=mp4]/b"));
        }
        cmd.add("https://www.youtube.com/watch?v=" + videoId);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String resultLine = null;
        String lastError = null;
        int part = 0;
        double lastPercent = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(RESULT)) {
                    resultLine = line.substring(RESULT.length());
                    continue;
                }
                if (line.startsWith("ERROR:")) {
                    lastError = line.substring(6).strip();
                }
                if (line.startsWith("[download] Destination:")) {
                    part++;
                }
                if (line.startsWith("[Merger]")) {
                    status.accept("Joining picture and sound…");
                }
                Matcher m = PERCENT.matcher(line);
                if (m.find()) {
                    double pct = Double.parseDouble(m.group(1));
                    if ((int) pct != (int) lastPercent) {
                        String what = ffmpeg == null ? "Downloading" : part <= 1 ? "Picture" : "Sound";
                        status.accept(what + " " + (int) pct + "%");
                    }
                    lastPercent = pct;
                }
            }
        }
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("The download was stopped.");
        }
        if (exit != 0 || resultLine == null) {
            throw new IOException(lastError != null ? "YouTube download failed: " + lastError
                    : "YouTube download failed (yt-dlp exit " + exit + ").");
        }
        String[] f = resultLine.split("\\|");
        Path file = Path.of(f[0]);
        if (!Files.isRegularFile(file)) {
            throw new IOException("The downloaded file is missing: " + file);
        }
        log.info("Downloaded YouTube video {} to {}", videoId, file);
        return new Result(file, number(f, 1), number(f, 2), seconds(f, 3));
    }

    private static Integer number(String[] f, int i) {
        try {
            return i < f.length ? (int) Double.parseDouble(f[i]) : null;
        } catch (NumberFormatException e) {
            return null; // "NA"
        }
    }

    private static Long seconds(String[] f, int i) {
        try {
            return i < f.length ? Math.round(Double.parseDouble(f[i]) * 1000) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** yt-dlp.exe in tools/, fetched from GitHub the first time. */
    private Path ensureYtDlp(Consumer<String> status) throws IOException {
        Path exe = config.paths().root().resolve("tools").resolve("yt-dlp.exe");
        if (Files.isRegularFile(exe) && Files.size(exe) > 1_000_000) {
            return exe;
        }
        status.accept("Getting the downloader (first time only)…");
        Files.createDirectories(exe.getParent());
        Path tmp = exe.resolveSibling("yt-dlp.exe.download");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(YT_DLP_URL))
                    .header("User-Agent", "LiveCat-Multimedia").timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                throw new IOException("Could not get the YouTube downloader (HTTP " + res.statusCode() + ").");
            }
            try (InputStream in = res.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            log.info("Installed yt-dlp at {}", exe);
            return exe;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The download was stopped.");
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e.getMessage() != null && e.getMessage().startsWith("Could not")
                    ? e : new IOException("Could not get the YouTube downloader. Check the internet. (" + e.getMessage() + ")", e);
        }
    }

    private static void runQuiet(Path exe, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            log.warn("Could not update yt-dlp: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
