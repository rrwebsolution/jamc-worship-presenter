package com.jamc.worship.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clap sync test: measures how late the voice is against the camera in a short test recording of Program.
 * <p>
 * The person claps a few times in front of the camera. In the sound a clap is a sudden loud spike; in the picture it
 * is a sudden change from one frame to the next (the hands meeting). The delay that lines the sound claps up with the
 * picture changes best is how late the sound is. Positive = the voice comes after the picture.
 */
public final class SyncTest {

    private static final Logger log = LoggerFactory.getLogger(SyncTest.class);

    /** How long the test records. */
    public static final int RECORD_SECONDS = 8;
    /** Audio is cut into chunks of this many ms (its loudest sample each). */
    static final int CHUNK_MS = 10;
    /** Sound later than the picture is searched up to this far, earlier up to {@link #MIN_LATE_MS}. */
    static final int MAX_LATE_MS = 2000;
    static final int MIN_LATE_MS = -1000;
    /** Two claps closer than this are one clap (its echo). */
    static final double CLAP_GAP_S = 0.35;

    /**
     * @param audioLateMs how late the sound is (negative = early); valid only when {@code problem} is null
     * @param claps       claps heard in the sound
     * @param matched     of those, claps that lined up with a picture change
     * @param problem     why no answer could be given, in words for the operator
     */
    public record Result(int audioLateMs, int claps, int matched, String problem) {
        public boolean ok() {
            return problem == null;
        }

        static Result fail(int claps, int matched, String problem) {
            return new Result(0, claps, matched, problem);
        }
    }

    private SyncTest() {
    }

    /** Reads the test recording with FFmpeg and measures it. Blocks for a few seconds. */
    public static Result measure(Path ffmpeg, Path file) throws IOException, InterruptedException {
        Series audio = series(ffmpeg, file, "0:a:0", "-af",
                "aresample=8000,asetnsamples=n=" + (8 * CHUNK_MS) + ":p=0,astats=metadata=1:reset=1,"
                        + "ametadata=print:key=lavfi.astats.Overall.Peak_level");
        Series video = series(ffmpeg, file, "0:v:0", "-vf",
                "scale=96:54,format=gray,signalstats,metadata=print:key=lavfi.signalstats.YDIF");
        double[] amp = new double[audio.values.length];
        for (int i = 0; i < amp.length; i++) {
            amp[i] = Math.pow(10, audio.values[i] / 20); // dB peak -> 0..1
        }
        Result r = estimate(audio.times, amp, video.times, video.values);
        log.info("Sync test: {} claps heard, {} seen, sound {} ms late{}", r.claps(), r.matched(), r.audioLateMs(),
                r.ok() ? "" : " (" + r.problem() + ")");
        return r;
    }

    // ------------------------------------------------------------------ the measurement

    /**
     * @param aT   start time of each audio chunk, s
     * @param aAmp loudest sample of each chunk, 0..1
     * @param vT   time of each video frame, s
     * @param vDif how much each frame differs from the one before
     */
    static Result estimate(double[] aT, double[] aAmp, double[] vT, double[] vDif) {
        if (aT.length < 50 || vT.length < 10) {
            return Result.fail(0, 0, "The test recording has no sound or no picture.");
        }
        List<Double> claps = claps(aT, aAmp);
        if (claps.size() < 2) {
            return Result.fail(claps.size(), 0, claps.isEmpty()
                    ? "No clap was heard. Unmute the microphone and clap loudly, close to it."
                    : "Only one clap was heard. Clap 3 times, about a second apart.");
        }
        double[] spike = spikes(vDif);
        double frame = medianStep(vT);

        // every delay from early to late, in 5 ms steps: how well the claps line up with picture changes
        int best = Integer.MIN_VALUE;
        double bestScore = -1;
        List<Integer> plateau = new ArrayList<>();
        for (int late = MIN_LATE_MS; late <= MAX_LATE_MS; late += 5) {
            double score = 0;
            for (double t : claps) {
                score += spikeNear(vT, spike, t - late / 1000.0, frame * 0.6);
            }
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = late;
                plateau.clear();
                plateau.add(late);
            } else if (Math.abs(score - bestScore) <= 1e-9 && !plateau.isEmpty() && late - plateau.getLast() == 5) {
                plateau.add(late); // same score for neighbouring delays: take the middle
            }
        }
        int late = plateau.isEmpty() ? best : (plateau.getFirst() + plateau.getLast()) / 2;
        int matched = 0;
        for (double t : claps) {
            if (spikeNear(vT, spike, t - late / 1000.0, frame * 0.6) >= 0.3) {
                matched++;
            }
        }
        if (matched < 2) {
            return Result.fail(claps.size(), matched,
                    "The claps were heard but not seen. Put the camera on Program and clap in front of it.");
        }
        // a frame shows the hands together up to one frame after they met: the picture is on average half a frame late
        late += (int) Math.round(frame * 500);
        return new Result(late, claps.size(), matched, null);
    }

    /** Times of sudden loud spikes: well above the room and far louder than the moment before. */
    static List<Double> claps(double[] t, double[] amp) {
        double max = Arrays.stream(amp).max().orElse(0);
        double floor = median(amp);
        double level = Math.max(0.2 * max, 4 * floor);
        List<Double> out = new ArrayList<>();
        if (max < 0.02) {
            return out; // silence
        }
        double last = -1e9;
        for (int i = 3; i < amp.length; i++) {
            double before = Math.max(amp[i - 1], Math.max(amp[i - 2], amp[i - 3]));
            if (amp[i] >= level && amp[i] >= 2.5 * before && t[i] - last >= CLAP_GAP_S) {
                out.add(t[i]);
                last = t[i];
            }
        }
        return out;
    }

    /** Frame changes above the usual movement around them, 0..1. */
    static double[] spikes(double[] dif) {
        double[] s = new double[dif.length];
        int r = 15;
        for (int i = 0; i < dif.length; i++) {
            double[] w = Arrays.copyOfRange(dif, Math.max(0, i - r), Math.min(dif.length, i + r + 1));
            s[i] = Math.max(0, dif[i] - median(w));
        }
        double max = Arrays.stream(s).max().orElse(0);
        if (max > 0) {
            for (int i = 0; i < s.length; i++) {
                s[i] /= max;
            }
        }
        return s;
    }

    private static double spikeNear(double[] vT, double[] spike, double t, double within) {
        int i = Arrays.binarySearch(vT, t);
        if (i < 0) {
            i = -i - 1;
        }
        double best = 0;
        for (int j = Math.max(0, i - 2); j <= Math.min(vT.length - 1, i + 2); j++) {
            if (Math.abs(vT[j] - t) <= within) {
                best = Math.max(best, spike[j]);
            }
        }
        return best;
    }

    private static double medianStep(double[] t) {
        double[] d = new double[t.length - 1];
        for (int i = 1; i < t.length; i++) {
            d[i - 1] = t[i] - t[i - 1];
        }
        double m = median(d);
        return m > 0 ? m : 1 / 30.0;
    }

    static double median(double[] v) {
        if (v.length == 0) {
            return 0;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    // ------------------------------------------------------------------ reading FFmpeg's numbers

    record Series(double[] times, double[] values) {
    }

    private static final Pattern TIME = Pattern.compile("pts_time:(-?[0-9.]+)");
    private static final Pattern VALUE = Pattern.compile("lavfi\\.[\\w.]+=(-?[0-9.]+|-?inf)");

    private static Series series(Path ffmpeg, Path file, String map, String filterFlag, String filter)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostats", "-i", file.toString(),
                "-map", map, filterFlag, filter, "-f", "null", "-")
                .redirectErrorStream(true).start();
        p.getOutputStream().close();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                lines.add(line);
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("FFmpeg took too long reading the test recording");
        }
        return parse(lines);
    }

    /** FFmpeg prints "frame:N pts:… pts_time:T" and then the value line for that frame. */
    static Series parse(List<String> lines) {
        List<double[]> pairs = new ArrayList<>();
        double time = Double.NaN;
        for (String line : lines) {
            Matcher t = TIME.matcher(line);
            if (t.find()) {
                time = Double.parseDouble(t.group(1));
                continue;
            }
            Matcher v = VALUE.matcher(line);
            if (v.find() && !Double.isNaN(time)) {
                String s = v.group(1);
                double value = s.endsWith("inf") ? -120 : Double.parseDouble(s);
                pairs.add(new double[]{time, value});
                time = Double.NaN;
            }
        }
        pairs.sort((a, b) -> Double.compare(a[0], b[0]));
        double[] ts = new double[pairs.size()];
        double[] vs = new double[pairs.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = pairs.get(i)[0];
            vs[i] = pairs.get(i)[1];
        }
        return new Series(ts, vs);
    }

    /** The microphone's new Sync Offset after a test: what it had, less how late the voice still is. */
    public static int newSyncMs(int currentMs, int audioLateMs) {
        int ms = currentMs - audioLateMs;
        return Math.clamp(Math.round(ms / 10.0f) * 10, 0, 5000);
    }
}
