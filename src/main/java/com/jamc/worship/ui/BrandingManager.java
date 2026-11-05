package com.jamc.worship.ui;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.service.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application's identity: its name, short name and logo / icon (Settings → Branding). Every
 * user-facing place that names the app or shows its icon asks here instead of using a fixed
 * "LiveCat Multimedia", and listens for changes so a new name or logo shows at once.
 *
 * <p>A custom logo is copied (as PNG) into the app's own data folder, so the original file may be
 * moved or deleted. Icons keep the logo's aspect ratio (transparent padding) and are cached per size.
 * This is runtime branding only: the installed .exe and its desktop shortcut keep the icon they were
 * built with.
 *
 * <p>The built-in icon and logo are in resources/branding. They are made from the artwork in
 * src/public/LiveCat logo by packaging/MakeBrandingAssets.java, which also makes the installer's app.ico.
 */
public final class BrandingManager {

    private static final Logger log = LoggerFactory.getLogger(BrandingManager.class);

    public static final String DEFAULT_NAME = "LiveCat Multimedia";
    public static final String DEFAULT_SHORT_NAME = "LiveCat";
    public static final String TAGLINE = "Present. Produce. Go Live.";
    public static final int MAX_NAME_LENGTH = 60;
    /** Sizes made for window icons (Windows picks the one it needs). */
    public static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    static final String KEY_NAME = "branding.appName";
    static final String KEY_SHORT_NAME = "branding.shortName";
    static final String KEY_LOGO = "branding.logo";
    static final String LOGO_FILE = "app-logo.png";
    /** The stored logo is at most this size (enough for the 256 px icon). */
    private static final int STORED_MAX = 512;
    private static final String BUILT_IN_ICON = "/branding/livecat-icon.png";
    private static final String BUILT_IN_LOGO = "/branding/livecat-logo.png";
    private static final Map<Integer, BufferedImage> DEFAULT_ICONS = new ConcurrentHashMap<>();

    private static volatile BrandingManager current;

    private final AppConfig config;
    private final Path dir;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, Image> iconCache = new HashMap<>();
    private BufferedImage logo;

    public BrandingManager(AppConfig config, AppPaths paths) {
        this.config = config;
        this.dir = paths.root().resolve("branding");
        logo = loadLogo();
    }

    /** Makes this the branding the whole app uses. */
    public static BrandingManager install(BrandingManager branding) {
        current = branding;
        return branding;
    }

    /** The app's name for dialogs and messages; the default name before the app has started. */
    public static String name() {
        BrandingManager b = current;
        return b == null ? DEFAULT_NAME : b.applicationName();
    }

    /** The short name (window titles such as "<short name> - Output"); the default before the app started. */
    public static String shortNameOrDefault() {
        BrandingManager b = current;
        return b == null ? DEFAULT_SHORT_NAME : b.shortName();
    }

    public static BrandingManager get() {
        return current;
    }

    public String applicationName() {
        String v = config.get(KEY_NAME, null);
        return v == null || v.isBlank() ? DEFAULT_NAME : v;
    }

    public String shortName() {
        String v = config.get(KEY_SHORT_NAME, null);
        return v == null || v.isBlank() ? DEFAULT_SHORT_NAME : v;
    }

    /** The custom logo, or null for the built-in one. */
    public BufferedImage customLogo() {
        return logo;
    }

    public boolean isDefault() {
        return config.get(KEY_NAME, null) == null && config.get(KEY_SHORT_NAME, null) == null && logo == null;
    }

    /** The app icon at {@code size} px: the logo fitted without distortion, or the built-in LiveCat icon. */
    public synchronized Image icon(int size) {
        return iconCache.computeIfAbsent(size, s -> logo == null ? defaultIcon(s) : fit(logo, s));
    }

    /** Window icons at every size Windows asks for. */
    public List<Image> windowIcons() {
        List<Image> out = new ArrayList<>();
        for (int s : ICON_SIZES) {
            out.add(icon(s));
        }
        return out;
    }

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    /**
     * Saves the branding. {@code newLogo} is a PNG/JPG to use from now on (copied into the app's data),
     * or null to keep the current logo; {@code defaultLogo} goes back to the built-in icon.
     */
    public void save(String name, String shortName, Path newLogo, boolean defaultLogo) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new ValidationException("Please enter the application name.");
        }
        if (n.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("The application name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        BufferedImage replacement = null;
        if (newLogo != null) {
            replacement = readImage(newLogo); // validate before anything is changed
        }
        String s = shortName == null ? "" : shortName.strip();
        config.set(KEY_NAME, n.equals(DEFAULT_NAME) ? null : n);
        config.set(KEY_SHORT_NAME, s.isEmpty() || s.equals(DEFAULT_SHORT_NAME) ? null : s);
        if (replacement != null) {
            Files.createDirectories(dir);
            ImageIO.write(shrink(replacement), "png", dir.resolve(LOGO_FILE).toFile());
            config.set(KEY_LOGO, LOGO_FILE);
        } else if (defaultLogo) {
            config.set(KEY_LOGO, null);
            Files.deleteIfExists(dir.resolve(LOGO_FILE));
        }
        config.save();
        reload();
    }

    /** Back to "LiveCat Multimedia" and the built-in icon. */
    public void resetToDefault() throws IOException {
        config.set(KEY_NAME, null);
        config.set(KEY_SHORT_NAME, null);
        config.set(KEY_LOGO, null);
        config.save();
        Files.deleteIfExists(dir.resolve(LOGO_FILE));
        reload();
    }

    /** Reads a PNG / JPG the operator picked; throws a readable message when it is not an image. */
    public static BufferedImage readImage(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"))) {
            throw new ValidationException("Please choose a PNG or JPG image (PNG keeps a transparent background).");
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new ValidationException("This file could not be read as an image.");
        }
        return img;
    }

    /** {@code img} inside a {@code size}×{@code size} square, aspect ratio kept, high-quality scaling. */
    public static BufferedImage fit(BufferedImage img, int size) {
        double scale = Math.min(size / (double) img.getWidth(), size / (double) img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage scaled = smoothScale(img, w, h);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(scaled, (size - w) / 2, (size - h) / 2, null);
        g.dispose();
        return out;
    }

    /** The built-in LiveCat icon, cached per size. */
    public static BufferedImage defaultIcon(int size) {
        return DEFAULT_ICONS.computeIfAbsent(size, s -> {
            BufferedImage icon = builtIn(BUILT_IN_ICON);
            return icon == null ? badge(s) : fit(icon, s);
        });
    }

    /** The full LiveCat logo with its name and tagline, for a white surface (its blue text is hard to read on dark); may be null. */
    public static BufferedImage defaultLogo() {
        return builtIn(BUILT_IN_LOGO);
    }

    private static BufferedImage builtIn(String resource) {
        try (var in = BrandingManager.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            log.warn("Could not read the built-in {}", resource, e);
            return null;
        }
    }

    /** Only if the built-in icon cannot be read: rounded blue square with a white "L". */
    private static BufferedImage badge(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x2B86FF), size, size, new Color(0x0F4FC2)));
        int arc = Math.max(4, size / 4);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, (int) (size * 0.62)));
        var fm = g.getFontMetrics();
        g.drawString("L", (size - fm.stringWidth("L")) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ internals

    private void reload() {
        synchronized (this) {
            logo = loadLogo();
            iconCache.clear();
        }
        listeners.forEach(Runnable::run);
    }

    private BufferedImage loadLogo() {
        String f = config.get(KEY_LOGO, null);
        if (f == null) {
            return null;
        }
        Path file = dir.resolve(f);
        try {
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (IOException e) {
            log.warn("Could not read the branding logo {}; using the default icon", file, e);
            return null;
        }
    }

    private static BufferedImage shrink(BufferedImage img) {
        int max = Math.max(img.getWidth(), img.getHeight());
        if (max <= STORED_MAX) {
            return toArgb(img);
        }
        double s = STORED_MAX / (double) max;
        return smoothScale(img, Math.max(1, (int) Math.round(img.getWidth() * s)), Math.max(1, (int) Math.round(img.getHeight() * s)));
    }

    private static BufferedImage toArgb(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Halves the image step by step before the last bicubic step, so small icons stay sharp. */
    private static BufferedImage smoothScale(BufferedImage img, int w, int h) {
        BufferedImage cur = toArgb(img);
        int cw = cur.getWidth();
        int ch = cur.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = draw(cur, cw, ch);
        }
        return draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
