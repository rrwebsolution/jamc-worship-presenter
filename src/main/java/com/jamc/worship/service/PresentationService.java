package com.jamc.worship.service;

import com.jamc.worship.model.Presentation;
import com.jamc.worship.presentation.PowerPointExportRenderer;
import com.jamc.worship.presentation.PresentationRenderer;
import com.jamc.worship.presentation.PresentationRenderers;
import com.jamc.worship.repository.PresentationRepository;
import com.jamc.worship.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

/**
 * The presentation library: imports PowerPoint files, renders their slides once into a local
 * cache, and serves the cached slides.
 *
 * <pre>
 * data/presentations/&lt;folder&gt;/
 *     presentation.json        name, source, renderer, slide titles and warnings
 *     slides/slide-001.png     1920x1080, letterboxed: what Program and the projector show
 *     thumbnails/slide-001.png 320x180: what the slide strips show
 * </pre>
 *
 * The original file is only read, never changed, moved or deleted. A refresh renders into a new
 * folder and switches over when it is complete, so a slide that is live keeps showing; folders no
 * longer referenced are removed by {@link #cleanUp()} at the next start.
 */
public class PresentationService {

    private static final Logger log = LoggerFactory.getLogger(PresentationService.class);
    public static final int SLIDE_WIDTH = 1920;
    public static final int SLIDE_HEIGHT = 1080;
    public static final int THUMB_WIDTH = 320;
    public static final int THUMB_HEIGHT = 180;
    private static final String MANIFEST = "presentation.json";

    /** Receives progress from a running import. Called on the import thread. */
    public interface Progress {
        void started(String name, int slideCount, String rendererName);

        void slideReady(PresentationSlide slide);

        default boolean isCancelled() {
            return false;
        }
    }

    /** One cached slide. Paths are absolute. */
    public record PresentationSlide(int number, String title, Path image, Path thumbnail, List<String> warnings) {
        public String label() {
            return title == null || title.isBlank() ? "Slide " + number : title;
        }
    }

    /** Contents of presentation.json. */
    public record Manifest(int format, String name, String source, String renderer, int slideCount, long sourceSize,
                           long sourceModified, String renderedAt, List<SlideEntry> slides) {
    }

    public record SlideEntry(int number, String title, List<String> warnings) {
    }

    /** Whether the original file still matches the cached slides. */
    public enum SourceState { CURRENT, CHANGED, MISSING }

    private final PresentationRepository repository;
    private final Path root;
    private final PresentationRenderers renderers;

    public PresentationService(PresentationRepository repository, Path presentationsDir, PresentationRenderers renderers) {
        this.repository = repository;
        this.root = presentationsDir;
        this.renderers = renderers;
    }

    public List<Presentation> list(String search) {
        return repository.findAll(search);
    }

    public Optional<Presentation> find(long id) {
        return repository.find(id);
    }

    public Optional<Presentation> findBySource(Path source) {
        String abs = source.toAbsolutePath().normalize().toString();
        return repository.findAll(null).stream().filter(p -> p.sourcePath().equalsIgnoreCase(abs)).findFirst();
    }

    /** True for files (and slide image folders) some renderer can import. */
    public boolean canImport(Path source) {
        return !renderers.candidates(source).isEmpty();
    }

    /** Imports a new presentation into the library. */
    public Presentation importFile(Path source, Progress progress) throws IOException {
        Path abs = source.toAbsolutePath().normalize();
        String name = abs.getFileName().toString();
        Rendered r = render(abs, name, progress);
        return repository.insert(new Presentation(null, name, abs.toString(), r.folder, r.manifest.slideCount(),
                r.manifest.renderer(), r.manifest.sourceSize(), r.manifest.sourceModified(), warningCount(r.manifest), null, null));
    }

    /**
     * Renders the presentation again from its file (or from {@code newSource}, when the file was
     * moved). The old cache stays in place until the next start, so whatever is live keeps showing.
     */
    public Presentation refresh(long id, Path newSource, Progress progress) throws IOException {
        Presentation p = repository.find(id).orElseThrow(() -> new IOException("The presentation is no longer in the library."));
        Path source = (newSource != null ? newSource : Path.of(p.sourcePath())).toAbsolutePath().normalize();
        if (!Files.exists(source)) {
            throw new IOException("The original file was not found:\n" + source);
        }
        Rendered r = render(source, p.name(), progress);
        Presentation updated = new Presentation(p.id(), p.name(), source.toString(), r.folder, r.manifest.slideCount(),
                r.manifest.renderer(), r.manifest.sourceSize(), r.manifest.sourceModified(), warningCount(r.manifest),
                p.importedAt(), LocalDateTime.now().toString());
        repository.update(updated);
        return updated;
    }

    public Presentation rename(long id, String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Enter a name for the presentation.");
        }
        Presentation p = repository.find(id).orElseThrow(() -> new ValidationException("The presentation no longer exists."));
        Presentation renamed = p.withName(name.strip());
        repository.update(renamed);
        return renamed;
    }

    /** Removes the library entry and its cached slides. The original PowerPoint file is not touched. */
    public void remove(long id) {
        Optional<Presentation> p = repository.find(id);
        repository.delete(id);
        p.ifPresent(x -> deleteQuietly(root.resolve(x.folder())));
    }

    /** The cached slides, in order. Missing images (e.g. after restoring a backup) are still listed. */
    public List<PresentationSlide> slides(Presentation p) {
        Path dir = root.resolve(p.folder());
        Manifest m = readManifest(dir);
        List<PresentationSlide> out = new ArrayList<>();
        int count = m != null ? m.slides().size() : p.slideCount();
        for (int i = 0; i < count; i++) {
            SlideEntry e = m != null ? m.slides().get(i) : new SlideEntry(i + 1, null, List.of());
            out.add(new PresentationSlide(e.number(), e.title(), slidePath(dir, e.number()), thumbPath(dir, e.number()),
                    e.warnings() == null ? List.of() : e.warnings()));
        }
        return out;
    }

    /** Cached small picture of slide {@code number} (1-based); may not exist. */
    public Path thumbnail(Presentation p, int number) {
        return thumbPath(root.resolve(p.folder()), number);
    }

    /** True if the slide images are present (they are absent after restoring a database without them). */
    public boolean isCached(Presentation p) {
        return Files.isRegularFile(slidePath(root.resolve(p.folder()), 1));
    }

    public SourceState sourceState(Presentation p) {
        Path source = Path.of(p.sourcePath());
        try {
            if (!Files.exists(source)) {
                return SourceState.MISSING;
            }
            Stamp s = stamp(source);
            return s.size == p.sourceSize() && s.modified == p.sourceModified() ? SourceState.CURRENT : SourceState.CHANGED;
        } catch (IOException e) {
            return SourceState.MISSING;
        }
    }

    /** Deletes cache folders that no library entry uses (left by refreshes or interrupted imports). */
    public int cleanUp() {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Set<String> used = new HashSet<>();
        for (Presentation p : repository.findAll(null)) {
            used.add(p.folder().toLowerCase(Locale.ROOT));
        }
        int removed = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                if (!used.contains(d.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    deleteQuietly(d);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean the presentation cache", e);
        }
        if (removed > 0) {
            log.info("Removed {} unused presentation cache folder(s)", removed);
        }
        return removed;
    }

    // ------------------------------------------------------------------ rendering

    private record Rendered(String folder, Manifest manifest) {
    }

    private Rendered render(Path source, String name, Progress progress) throws IOException {
        List<PresentationRenderer> candidates = renderers.candidates(source);
        if (candidates.isEmpty()) {
            throw new IOException("This file type cannot be imported. Choose a PowerPoint file (.pptx or .ppt) "
                    + "or a folder of slide images.");
        }
        Stamp stamp = Files.isDirectory(source) ? new Stamp(0, 0) : stamp(source);
        Files.createDirectories(root);
        IOException last = null;
        for (PresentationRenderer renderer : candidates) {
            String folder = UUID.randomUUID().toString();
            Path dir = root.resolve(folder);
            try {
                Manifest m = renderWith(renderer, source, name, dir, stamp, progress);
                return new Rendered(folder, m);
            } catch (CancellationException e) {
                deleteQuietly(dir);
                throw e;
            } catch (IOException | RuntimeException e) {
                deleteQuietly(dir);
                log.warn("{} could not render {}: {}", renderer.name(), source, e.getMessage());
                last = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
        }
        throw last;
    }

    private Manifest renderWith(PresentationRenderer renderer, Path source, String name, Path dir, Stamp stamp,
                                Progress progress) throws IOException {
        log.info("Rendering {} with {}", source, renderer.name());
        Files.createDirectories(dir.resolve("slides"));
        Files.createDirectories(dir.resolve("thumbnails"));
        List<SlideEntry> entries = new ArrayList<>();
        try (PresentationRenderer.Document doc = renderer.open(source)) {
            int count = doc.slideCount();
            if (count <= 0) {
                throw new IOException("The presentation has no slides.");
            }
            progress.started(name, count, renderer.name());
            for (int i = 0; i < count; i++) {
                if (progress.isCancelled()) {
                    throw new CancellationException("Import cancelled");
                }
                List<String> warnings = new ArrayList<>();
                BufferedImage raw;
                try {
                    raw = doc.render(i, SLIDE_WIDTH, SLIDE_HEIGHT, warnings);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    if (renderer instanceof PowerPointExportRenderer) { // let the caller fall back to POI
                        throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                    }
                    log.warn("Slide {} of {} could not be rendered", i + 1, source, e);
                    warnings.add("This slide could not be drawn: " + e.getMessage());
                    raw = placeholder(i + 1);
                }
                BufferedImage full = fit(raw, SLIDE_WIDTH, SLIDE_HEIGHT);
                Path image = slidePath(dir, i + 1);
                Path thumb = thumbPath(dir, i + 1);
                ImageIO.write(full, "png", image.toFile());
                ImageIO.write(fit(full, THUMB_WIDTH, THUMB_HEIGHT), "png", thumb.toFile());
                String title = doc.slideTitle(i);
                if (!warnings.isEmpty()) {
                    log.info("Slide {} of {}: {}", i + 1, source.getFileName(), String.join(" ", warnings));
                }
                entries.add(new SlideEntry(i + 1, title, List.copyOf(warnings)));
                progress.slideReady(new PresentationSlide(i + 1, title, image, thumb, List.copyOf(warnings)));
            }
        }
        Manifest m = new Manifest(1, name, source.toString(), renderer.name(), entries.size(), stamp.size, stamp.modified,
                LocalDateTime.now().toString(), entries);
        Files.writeString(dir.resolve(MANIFEST), Json.write(m), StandardCharsets.UTF_8);
        return m;
    }

    /** Scales {@code img} into a {@code w}x{@code h} frame, centered on black (letterbox / pillarbox). */
    static BufferedImage fit(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
            int sw = (int) Math.round(img.getWidth() * scale);
            int sh = (int) Math.round(img.getHeight() * scale);
            BufferedImage src = img;
            // Halve repeatedly first: one big bicubic step from 1920 to 320 looks grainy
            while (src.getWidth() / 2 >= sw && src.getHeight() / 2 >= sh) {
                BufferedImage half = new BufferedImage(src.getWidth() / 2, src.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
                Graphics2D hg = half.createGraphics();
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(src, 0, 0, half.getWidth(), half.getHeight(), null);
                hg.dispose();
                src = half;
            }
            g.drawImage(src, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage placeholder(int number) {
        BufferedImage img = new BufferedImage(SLIDE_WIDTH, SLIDE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x111827));
        g.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x94A3B8));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 56));
        String s = "Slide " + number + " could not be displayed";
        g.drawString(s, (SLIDE_WIDTH - g.getFontMetrics().stringWidth(s)) / 2, SLIDE_HEIGHT / 2);
        g.dispose();
        return img;
    }

    private static Path slidePath(Path dir, int number) {
        return dir.resolve("slides").resolve("slide-%03d.png".formatted(number));
    }

    private static Path thumbPath(Path dir, int number) {
        return dir.resolve("thumbnails").resolve("slide-%03d.png".formatted(number));
    }

    private static Manifest readManifest(Path dir) {
        try {
            Path f = dir.resolve(MANIFEST);
            return Files.isRegularFile(f) ? Json.readOrNull(Files.readString(f, StandardCharsets.UTF_8), Manifest.class) : null;
        } catch (IOException e) {
            log.warn("Could not read {}", dir.resolve(MANIFEST), e);
            return null;
        }
    }

    private static int warningCount(Manifest m) {
        return (int) m.slides().stream().filter(s -> !s.warnings().isEmpty()).count();
    }

    private record Stamp(long size, long modified) {
    }

    private static Stamp stamp(Path file) throws IOException {
        return new Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    log.debug("Could not delete {}", f);
                }
            });
        } catch (IOException e) {
            log.warn("Could not delete {}", dir, e);
        }
    }
}
