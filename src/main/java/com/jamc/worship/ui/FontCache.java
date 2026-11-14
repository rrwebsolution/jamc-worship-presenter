package com.jamc.worship.ui;

import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code font.deriveFont(Font.BOLD, 10f)} without the memory leak.
 *
 * <p>The UI font FlatLaf takes from Windows is a "created" composite font (Segoe UI plus fallbacks). Java gives
 * every style change of such a font a brand-new {@code CompositeFont} with its own glyph caches
 * ({@code FontManager.getNewComposite}), and its font-metrics cache keeps those through soft references, which a
 * large heap clears only after hours. Painting a bold label 50 times a second therefore piled up hundreds of fonts
 * a minute (measured 2026-11-15: ~230 MB after a few idle minutes, growing without end), and with Chrome open the
 * PC ran out of RAM and LiveCat lagged or froze. Here each base font, style and size is derived once and reused.
 */
public final class FontCache {

    private record Key(Font base, int style, float size) {
    }

    private static final int MAX = 512;
    private static final Map<Key, Font> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Font> eldest) {
            return size() > MAX;
        }
    };

    private FontCache() {
    }

    /** Same as {@code base.deriveFont(style, size)}, made once per base font, style and size. */
    public static Font derive(Font base, int style, float size) {
        Key k = new Key(base, style, size);
        synchronized (CACHE) {
            Font f = CACHE.get(k);
            if (f == null) {
                f = base.deriveFont(style, size);
                CACHE.put(k, f);
            }
            return f;
        }
    }

    /** Same as {@code base.deriveFont(style)}, made once per base font and style. */
    public static Font derive(Font base, int style) {
        return derive(base, style, base.getSize2D());
    }
}
