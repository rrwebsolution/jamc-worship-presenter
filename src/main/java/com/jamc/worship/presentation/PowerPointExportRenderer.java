package com.jamc.worship.presentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Exact rendering through Microsoft PowerPoint itself (Windows COM automation via PowerShell), used
 * when PowerPoint is installed. PowerPoint exports each slide as a PNG, so fonts, SmartArt, charts
 * and effects look exactly as designed. Nothing is shown on screen and PowerPoint is only closed if
 * the operator had no other presentation open in it.
 *
 * <p>If PowerPoint is missing, shows a dialog, or takes too long, the caller falls back to
 * {@link PoiPresentationRenderer}.
 */
public final class PowerPointExportRenderer implements PresentationRenderer {

    private static final Logger log = LoggerFactory.getLogger(PowerPointExportRenderer.class);
    private static final long OPEN_TIMEOUT_S = 120;
    private static final long SLIDE_TIMEOUT_S = 60;
    private volatile Boolean installed;
    /** COM program id: {@code PowerPoint.Application}, or {@code KWPP.Application} for WPS Presentation. */
    private final String progId;
    private final String displayName;

    /** Microsoft PowerPoint. */
    public PowerPointExportRenderer() {
        this("PowerPoint.Application", "Microsoft PowerPoint");
    }

    /** Any presentation program with PowerPoint's automation interface (WPS Presentation: KWPP.Application). */
    public PowerPointExportRenderer(String progId, String displayName) {
        this.progId = progId;
        this.displayName = displayName;
    }

    private static final String SCRIPT = """
            param([string]$ProgId, [string]$Source, [string]$Out, [int]$MaxW, [int]$MaxH)
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            function Say([string]$s) { [Console]::Out.WriteLine($s); [Console]::Out.Flush() }
            $app = $null; $pres = $null
            try {
              $app = New-Object -ComObject $ProgId
              $pres = $app.Presentations.Open($Source, -1, 0, 0)
              $sw = $pres.PageSetup.SlideWidth; $sh = $pres.PageSetup.SlideHeight
              $scale = [Math]::Min($MaxW / $sw, $MaxH / $sh)
              $w = [int][Math]::Round($sw * $scale); $h = [int][Math]::Round($sh * $scale)
              $n = $pres.Slides.Count
              Say "COUNT $n"
              for ($i = 1; $i -le $n; $i++) {
                $slide = $pres.Slides.Item($i)
                $title = ''
                try { if ($slide.Shapes.HasTitle) { $title = $slide.Shapes.Title.TextFrame.TextRange.Text } } catch {}
                $slide.Export((Join-Path $Out ('slide-{0:D3}.png' -f $i)), 'PNG', $w, $h)
                Say ("DONE $i " + (($title -replace '\\s+', ' ').Trim()))
              }
            } catch {
              Say ("ERROR " + ($_.Exception.Message -replace '\\s+', ' '))
              exit 2
            } finally {
              if ($pres -ne $null) { try { $pres.Close() } catch {} }
              if ($app -ne $null) {
                try { if ($app.Presentations.Count -eq 0) { $app.Quit() } } catch {}
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($app)
              }
            }
            """;

    @Override
    public String name() {
        return displayName;
    }

    @Override
    public boolean canOpen(Path source) {
        String n = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(source) && (n.endsWith(".pptx") || n.endsWith(".ppt") || n.endsWith(".pptm")
                || n.endsWith(".ppsx") || n.endsWith(".pps"));
    }

    /** True if PowerPoint is registered for automation on this computer (checked once). */
    @Override
    public boolean isAvailable() {
        Boolean b = installed;
        if (b == null) {
            b = detect();
            installed = b;
        }
        return b;
    }

    private boolean detect() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCR\\" + progId + "\\CLSID").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            log.info("{} {} for slide rendering", displayName, ok ? "is available" : "is not installed");
            return ok;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Document open(Path source) throws IOException {
        Path work = Files.createTempDirectory("jamc-pptexport");
        Path script = work.resolve("export.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
        Path out = Files.createDirectory(work.resolve("slides"));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-ProgId", progId, "-Source", source.toAbsolutePath().toString(), "-Out", out.toString(),
                "-MaxW", "1920", "-MaxH", "1080").redirectErrorStream(true).start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // process ended
            } finally {
                lines.add("\u0000EOF");
            }
        }, "pptexport-reader");
        reader.setDaemon(true);
        reader.start();

        ExportDocument doc = new ExportDocument(process, lines, work, out);
        try {
            String first = doc.next("COUNT", OPEN_TIMEOUT_S);
            doc.count = Integer.parseInt(first.substring("COUNT ".length()).strip());
            return doc;
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private static final class ExportDocument implements Document {
        private final Process process;
        private final BlockingQueue<String> lines;
        private final Path work;
        private final Path out;
        private final Map<Integer, String> titles = new HashMap<>();
        private int count;
        private int exported;

        ExportDocument(Process process, BlockingQueue<String> lines, Path work, Path out) {
            this.process = process;
            this.lines = lines;
            this.work = work;
            this.out = out;
        }

        /** Waits for the next line starting with {@code prefix}; ERROR lines and timeouts throw. */
        String next(String prefix, long timeoutSeconds) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                String line;
                try {
                    line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                if (line == null) {
                    throw new IOException("PowerPoint did not respond in time.");
                }
                if (line.startsWith("\u0000EOF")) {
                    throw new IOException("PowerPoint stopped before the export finished.");
                }
                if (line.startsWith("ERROR ")) {
                    throw new IOException("PowerPoint: " + line.substring(6));
                }
                if (line.startsWith(prefix + " ")) {
                    return line;
                }
                log.debug("PowerPoint export: {}", line);
            }
        }

        @Override
        public int slideCount() {
            return count;
        }

        @Override
        public String slideTitle(int index) {
            return titles.get(index);
        }

        @Override
        public BufferedImage render(int index, int maxWidth, int maxHeight, List<String> warnings) throws IOException {
            while (exported <= index) {
                String line = next("DONE", SLIDE_TIMEOUT_S);
                String[] parts = line.split(" ", 3);
                int n = Integer.parseInt(parts[1]);
                if (parts.length > 2 && !parts[2].isBlank()) {
                    titles.put(n - 1, parts[2].strip());
                }
                exported = n;
            }
            Path file = out.resolve("slide-%03d.png".formatted(index + 1));
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) {
                throw new IOException("PowerPoint did not export slide " + (index + 1));
            }
            Files.deleteIfExists(file);
            return img;
        }

        @Override
        public void close() {
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            try (Stream<Path> files = Files.walk(work)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // temp folder: Windows cleans it eventually
            }
        }
    }
}
