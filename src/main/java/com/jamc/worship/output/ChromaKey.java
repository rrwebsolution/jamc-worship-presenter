package com.jamc.worship.output;

import com.jamc.worship.model.SourceConfig;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chroma key (like OBS): the key colour (green by default) of a camera or NDI picture becomes transparent, so the
 * layers under it show through. Many programs that send NDI (graphics, lower thirds, a presenter over a green screen)
 * use a green background for exactly this.
 *
 * <p>The colour is compared by its chroma (Cb/Cr) only, so the shadows and bright spots of the green screen are keyed
 * too. Inside {@code similarity} a pixel is fully transparent, then it fades out over {@code smoothness}. Spill
 * reduction takes the green tinge off edges and hair.
 *
 * <p>Each surface (Preview, Program, projector…) has its own instance. A frame is keyed once and reused while the
 * camera has not sent a new picture, so a still graphic costs nothing after the first frame.
 */
final class ChromaKey {

    /** OBS-like defaults (in percent). */
    static final int DEFAULT_SIMILARITY = 40;
    static final int DEFAULT_SMOOTHNESS = 8;
    static final int GREEN = 0x00FF00;

    private static final class Entry {
        BufferedImage source;
        int signature;
        int settings;
        BufferedImage keyed;
    }

    private final Map<SourceConfig, Entry> cache = new WeakHashMap<>();

    /** Whether a source asks for it. */
    static boolean wanted(SourceConfig c) {
        return c != null && Boolean.TRUE.equals(c.chromaKey);
    }

    /** The frame with its key colour made transparent (the same image object while nothing changed). */
    BufferedImage apply(BufferedImage frame, SourceConfig c) {
        if (frame == null || !wanted(c)) {
            return frame;
        }
        int key = c.chromaKeyColor == null ? GREEN : c.chromaKeyColor & 0xFFFFFF;
        int similarity = c.chromaSimilarity == null ? DEFAULT_SIMILARITY : Math.clamp(c.chromaSimilarity, 1, 100);
        int smoothness = c.chromaSmoothness == null ? DEFAULT_SMOOTHNESS : Math.clamp(c.chromaSmoothness, 0, 100);
        boolean spill = !Boolean.FALSE.equals(c.chromaSpill);
        int settings = ((key * 31 + similarity) * 31 + smoothness) * 2 + (spill ? 1 : 0);
        Entry e = cache.computeIfAbsent(c, k -> new Entry());
        int sig = signature(frame);
        if (e.keyed != null && e.source == frame && e.signature == sig && e.settings == settings
                && e.keyed.getWidth() == frame.getWidth() && e.keyed.getHeight() == frame.getHeight()) {
            return e.keyed; // no new picture since the last time
        }
        if (e.keyed == null || e.keyed.getWidth() != frame.getWidth() || e.keyed.getHeight() != frame.getHeight()) {
            e.keyed = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        }
        key(frame, e.keyed, key, similarity, smoothness, spill);
        e.source = frame;
        e.signature = sig;
        e.settings = settings;
        return e.keyed;
    }

    /** A cheap fingerprint of a frame (a few pixels), to see whether the camera sent a new picture into the same image. */
    static int signature(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int s = w * 31 + h;
        for (int i = 1; i <= 7; i++) {
            for (int j = 1; j <= 7; j++) {
                s = s * 31 + img.getRGB(w * i / 8, h * j / 8);
            }
        }
        return s;
    }

    /** Keys {@code src} into {@code dst} (same size, ARGB). */
    static void key(BufferedImage src, BufferedImage dst, int keyRgb, int similarityPct, int smoothnessPct, boolean spill) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] in = pixels(src);
        int[] out = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        int kr = (keyRgb >> 16) & 0xFF;
        int kg = (keyRgb >> 8) & 0xFF;
        int kb = keyRgb & 0xFF;
        int kcb = cb(kr, kg, kb);
        int kcr = cr(kr, kg, kb);
        // chroma distance runs 0..~360 for these scaled Cb/Cr; the percentages map onto it like OBS (0..1000 / 1000)
        double full = 362.0;
        double inner = full * similarityPct / 100.0 * 0.5;
        double band = Math.max(1.0, full * smoothnessPct / 100.0 * 0.5);
        int innerSq = (int) (inner * inner);
        int outerSq = (int) ((inner + band) * (inner + band));
        boolean greenKey = kg >= kr && kg >= kb;
        boolean blueKey = kb > kg && kb >= kr;
        for (int i = 0, n = w * h; i < n; i++) {
            int p = in[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int dcb = cb(r, g, b) - kcb;
            int dcr = cr(r, g, b) - kcr;
            int d2 = dcb * dcb + dcr * dcr;
            int a;
            if (d2 <= innerSq) {
                out[i] = 0;
                continue;
            } else if (d2 >= outerSq) {
                a = 255;
            } else {
                a = (int) Math.round((Math.sqrt(d2) - inner) / band * 255);
            }
            if (spill) {
                // take the key colour's tinge off what is left (green spill on edges and hair)
                if (greenKey) {
                    g = Math.min(g, Math.max(r, b));
                } else if (blueKey) {
                    b = Math.min(b, Math.max(r, g));
                }
            }
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int[] pixels(BufferedImage img) {
        if ((img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_INT_ARGB)
                && img.getRaster().getDataBuffer() instanceof DataBufferInt db
                && img.getRaster().getSampleModelTranslateX() == 0 && img.getRaster().getSampleModelTranslateY() == 0
                && db.getData().length == img.getWidth() * img.getHeight()) {
            return db.getData();
        }
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static int cb(int r, int g, int b) {
        return (-43 * r - 85 * g + 128 * b) >> 8;
    }

    private static int cr(int r, int g, int b) {
        return (128 * r - 107 * g - 21 * b) >> 8;
    }
}
