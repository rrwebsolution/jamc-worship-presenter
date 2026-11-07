package com.jamc.worship.stream;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One audio filter on a mixer channel (Noise Suppression, Noise Gate, Gain, Compressor, Limiter), like OBS's audio
 * filters. It becomes part of the FFmpeg sound chain of the recording and the livestream, so it really changes what
 * viewers hear. Plain fields so it is stored as JSON in the settings.
 */
public final class AudioFilter {

    /** A setting of a filter type: its key, label, unit, range and default. */
    public record Param(String key, String label, String unit, double min, double max, double def, double step) {
    }

    public enum Type {
        NOISE_SUPPRESSION("Noise Suppression", List.of(
                new Param("reduction", "Reduction", "dB", 1, 60, 20, 1))),
        NOISE_GATE("Noise Gate", List.of(
                new Param("threshold", "Close below", "dB", -80, 0, -40, 1),
                new Param("attack", "Attack", "ms", 1, 500, 25, 1),
                new Param("release", "Release", "ms", 10, 2000, 150, 10))),
        GAIN("Gain", List.of(
                new Param("gain", "Gain", "dB", -30, 30, 0, 0.5))),
        COMPRESSOR("Compressor", List.of(
                new Param("ratio", "Ratio", ":1", 1, 20, 4, 0.5),
                new Param("threshold", "Threshold", "dB", -60, 0, -18, 1),
                new Param("attack", "Attack", "ms", 1, 500, 6, 1),
                new Param("release", "Release", "ms", 10, 5000, 60, 10),
                new Param("makeup", "Output gain", "dB", 0, 24, 0, 0.5))),
        LIMITER("Limiter", List.of(
                new Param("threshold", "Threshold", "dB", -30, 0, -6, 0.5),
                new Param("release", "Release", "ms", 1, 1000, 60, 1)));

        private final String label;
        private final List<Param> params;

        Type(String label, List<Param> params) {
            this.label = label;
            this.params = params;
        }

        public String label() {
            return label;
        }

        public List<Param> params() {
            return params;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public Type type;
    public String name;
    public boolean enabled = true;
    public Map<String, Double> params = new LinkedHashMap<>();

    public AudioFilter() {
    }

    /** A new filter of this type with its default settings. */
    public static AudioFilter of(Type type) {
        AudioFilter f = new AudioFilter();
        f.type = type;
        f.name = type.label();
        for (Param p : type.params()) {
            f.params.put(p.key(), p.def());
        }
        return f;
    }

    public AudioFilter copy() {
        AudioFilter f = new AudioFilter();
        f.type = type;
        f.name = name;
        f.enabled = enabled;
        f.params = new LinkedHashMap<>(params);
        return f;
    }

    /** A setting's value, kept inside its range (the default when missing). */
    public double value(String key) {
        for (Param p : type.params()) {
            if (p.key().equals(key)) {
                Double v = params.get(key);
                return v == null || v.isNaN() ? p.def() : Math.clamp(v, p.min(), p.max());
            }
        }
        return 0;
    }

    /** The FFmpeg audio filter for this filter, e.g. "afftdn=nr=20:nf=-50"; null when switched off or unknown. */
    public String ffmpeg() {
        if (!enabled || type == null) {
            return null;
        }
        return switch (type) {
            case NOISE_SUPPRESSION -> "afftdn=nr=" + num(value("reduction")) + ":nf=-50";
            case NOISE_GATE -> "agate=threshold=" + num(linear(value("threshold"))) + ":range=0.001:attack="
                    + num(value("attack")) + ":release=" + num(value("release"));
            case GAIN -> "volume=" + num(value("gain")) + "dB";
            case COMPRESSOR -> "acompressor=threshold=" + num(Math.max(0.000976563, linear(value("threshold"))))
                    + ":ratio=" + num(value("ratio")) + ":attack=" + num(value("attack")) + ":release="
                    + num(value("release")) + ":makeup=" + num(Math.clamp(linear(value("makeup")), 1, 64));
            case LIMITER -> "alimiter=limit=" + num(Math.clamp(linear(value("threshold")), 0.0625, 1)) + ":release="
                    + num(value("release")) + ":level=0";
        };
    }

    static double linear(double db) {
        return Math.pow(10, db / 20);
    }

    static String num(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
