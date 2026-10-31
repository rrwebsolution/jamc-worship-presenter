package com.jamc.worship.render;

import com.jamc.worship.model.FontWeight;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Installed fonts and the font face to use for a family and weight.
 *
 * <p>Windows registers extra weights either as their own family ("Segoe UI Semibold") or as a
 * face name ("Montserrat SemiBold"); both are found here. A missing family falls back to
 * Segoe UI instead of Java's plain Dialog font, so a church copying its settings to a computer
 * without Montserrat still gets a clean look.
 */
public final class Fonts {

    public static final String FALLBACK_FAMILY = "Segoe UI";

    /** Lower-case family or face name to its exact spelling. */
    private static volatile Map<String, String> names;
    private static volatile List<String> families;

    private Fonts() {
    }

    private static Map<String, String> names() {
        Map<String, String> n = names;
        if (n == null) {
            synchronized (Fonts.class) {
                if (names == null) {
                    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                    Map<String, String> map = new HashMap<>();
                    String[] fams = ge.getAvailableFontFamilyNames(Locale.ROOT);
                    for (String f : fams) {
                        map.put(f.toLowerCase(Locale.ROOT), f);
                    }
                    for (Font f : ge.getAllFonts()) {
                        map.putIfAbsent(f.getFontName(Locale.ROOT).toLowerCase(Locale.ROOT), f.getFontName(Locale.ROOT));
                    }
                    families = List.copyOf(Arrays.asList(fams));
                    names = map;
                }
                n = names;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ fonts added in the app

    private static volatile java.nio.file.Path userDir;
    private static final java.util.Set<String> FONT_FILES = java.util.Set.of("ttf", "otf", "ttc");
    private static final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Loads the fonts the church added (Add Font…) from {@code dir}, so they work without being installed in
     * Windows and travel with backups. Call once at startup.
     */
    public static void loadUserFonts(java.nio.file.Path dir) {
        userDir = dir;
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var files = java.nio.file.Files.list(dir)) {
            files.filter(Fonts::isFontFile).forEach(Fonts::register);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(Fonts.class).warn("Could not read the fonts folder {}", dir, e);
        }
        reset();
    }

    /**
     * Add Font…: copies TTF / OTF files into the app's fonts folder and makes them available at once. Each
     * weight file of a family (e.g. Montserrat-Light, -SemiBold, -Black) gives the THICKNESS choices of that
     * font. Returns the font families added.
     */
    public static List<String> importFonts(List<java.nio.file.Path> files) throws java.io.IOException {
        java.nio.file.Path dir = userDir;
        if (dir == null) {
            throw new java.io.IOException("The fonts folder is not set up.");
        }
        java.nio.file.Files.createDirectories(dir);
        java.util.Set<String> added = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.nio.file.Path f : files) {
            if (!isFontFile(f)) {
                throw new java.io.IOException(f.getFileName() + " is not a TTF, OTF or TTC font file.");
            }
            Font[] fonts;
            try {
                fonts = Font.createFonts(f.toFile());
            } catch (java.awt.FontFormatException e) {
                throw new java.io.IOException(f.getFileName() + " is not a font this computer can read.", e);
            }
            java.nio.file.Path target = dir.resolve(f.getFileName().toString());
            if (!target.equals(f)) {
                java.nio.file.Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            for (Font font : fonts) {
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                added.add(font.getFamily(Locale.ROOT));
            }
        }
        reset();
        return new ArrayList<>(added);
    }

    /** Told (on the caller's thread) when fonts were added, so font pickers can refill. */
    public static void addListener(Runnable l) {
        listeners.add(l);
    }

    private static boolean isFontFile(java.nio.file.Path f) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot > 0 && FONT_FILES.contains(n.substring(dot + 1)) && java.nio.file.Files.isRegularFile(f);
    }

    private static void register(java.nio.file.Path f) {
        try {
            for (Font font : Font.createFonts(f.toFile())) {
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(Fonts.class).warn("Could not load the font {}", f.getFileName(), e);
        }
    }

    /** Forgets the cached font names (new fonts were registered). */
    private static void reset() {
        synchronized (Fonts.class) {
            names = null;
            families = null;
        }
        listeners.forEach(Runnable::run);
    }

    /** Which THICKNESS choices this family really has (its own weight faces; the rest fall back to plain or bold). */
    public static java.util.Set<FontWeight> realWeights(String family) {
        java.util.Set<FontWeight> out = java.util.EnumSet.of(FontWeight.REGULAR, FontWeight.BOLD);
        String fam = effectiveFamily(family);
        for (FontWeight w : FontWeight.values()) {
            for (String suffix : w.faceSuffixes()) {
                if (isInstalled(fam + " " + suffix)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** Installed font families, alphabetically (for font pickers). */
    public static List<String> families() {
        names();
        return families;
    }

    public static boolean isInstalled(String familyOrFace) {
        return familyOrFace != null && names().containsKey(familyOrFace.strip().toLowerCase(Locale.ROOT));
    }

    /** The first installed of {@code candidates}, else {@link #FALLBACK_FAMILY}. */
    public static String preferred(String... candidates) {
        for (String c : candidates) {
            if (isInstalled(c)) {
                return names().get(c.strip().toLowerCase(Locale.ROOT));
            }
        }
        return FALLBACK_FAMILY;
    }

    /** The family that will really be drawn for {@code family} (itself when installed). */
    public static String effectiveFamily(String family) {
        if (isInstalled(family)) {
            return names().get(family.strip().toLowerCase(Locale.ROOT));
        }
        return isInstalled(FALLBACK_FAMILY) ? FALLBACK_FAMILY : Font.SANS_SERIF;
    }

    /** A font of {@code size} points for the family and weight, using a dedicated weight face when installed. */
    public static Font create(String family, FontWeight weight, boolean italic, float size) {
        String fam = effectiveFamily(family);
        for (String suffix : weight.faceSuffixes()) {
            String face = fam + " " + suffix;
            if (isInstalled(face)) {
                String exact = names().get(face.toLowerCase(Locale.ROOT));
                return new Font(exact, italic ? Font.ITALIC : Font.PLAIN, 1).deriveFont(size);
            }
        }
        int style = (weight.isBold() ? Font.BOLD : Font.PLAIN) | (italic ? Font.ITALIC : 0);
        return new Font(fam, style, 1).deriveFont(size);
    }

    /** Families for a picker: the installed ones, with {@code current} kept even when it is not installed. */
    public static List<String> pickerFamilies(String current) {
        List<String> out = new ArrayList<>(families());
        if (current != null && !current.isBlank() && !isInstalled(current)) {
            out.addFirst(current);
        }
        return out;
    }
}
