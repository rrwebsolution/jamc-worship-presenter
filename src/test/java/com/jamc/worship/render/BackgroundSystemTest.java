package com.jamc.worship.render;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.media.MediaProbe;
import com.jamc.worship.model.BackgroundType;
import com.jamc.worship.model.FitMode;
import com.jamc.worship.model.Gradient;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.ThemeRepository;
import com.jamc.worship.service.ThemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gradient and Fit/Fill backgrounds, format detection and the video check's failure paths. */
class BackgroundSystemTest {

    @TempDir
    Path tempDir;

    private static SlideStyle base() {
        return SlideStyle.fallback();
    }

    @Test
    void gradientRunsBetweenItsColorsInItsDirection() {
        StyleOverrides o = new StyleOverrides();
        o.backgroundType = BackgroundType.GRADIENT;
        o.backgroundValue = new Gradient("#FF0000", "#0000FF", 90).format();
        BufferedImage img = new SlideRenderer(new ImageCache(null)).render(new Slide("g", "", null), o.applyTo(base()), 192, 108);
        Color top = new Color(img.getRGB(96, 1));
        Color bottom = new Color(img.getRGB(96, 106));
        assertTrue(top.getRed() > 200 && top.getBlue() < 60, "top is the start color, got " + top);
        assertTrue(bottom.getBlue() > 200 && bottom.getRed() < 60, "bottom is the end color, got " + bottom);
        assertEquals(Gradient.DEFAULT, Gradient.parse("nonsense"));
        assertEquals(new Gradient("#112233", "#445566", 45), Gradient.parse("#112233, #445566, 45"));
    }

    @Test
    void fitLetterboxesAndFillCovers() throws Exception {
        // a square red image on a 16:9 slide with a green "behind" color
        Path png = tempDir.resolve("square.png");
        BufferedImage sq = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var g = sq.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ImageIO.write(sq, "png", png.toFile());
        ImageCache cache = new ImageCache(null);
        assertNotNull(cache.getBlocking(png.toString()));
        SlideRenderer r = new SlideRenderer(cache);
        SlideStyle image = new SlideStyle(BackgroundType.IMAGE, png.toString(), "#00FF00", 0, "Segoe UI", 72, true, false,
                "#FFFFFF", null, null, 1, false, false, "#000000", 2);

        BufferedImage fill = r.render(new Slide("x", "", null), image, 160, 90);
        assertEquals(Color.RED.getRGB(), fill.getRGB(2, 45), "FILL covers the edges");

        BufferedImage fit = r.render(new Slide("x", "", null), image.withBackgroundFit(FitMode.FIT), 160, 90);
        assertEquals(Color.GREEN.getRGB(), fit.getRGB(2, 45), "FIT leaves bars in the background color");
        assertEquals(Color.RED.getRGB(), fit.getRGB(80, 45));
    }

    @Test
    void fitIsStoredWithTheThemeAndInOverrides() {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        ThemeService themes = new ThemeService(new ThemeRepository(db), new SettingsRepository(db));
        Theme t = themes.defaultTheme();
        SlideStyle videoFit = new SlideStyle(BackgroundType.VIDEO, "media/backgrounds/loop.mkv", "#000000", 0.3, "Segoe UI",
                72, true, false, "#FFFFFF", null, null, 1.1, true, false, "#000000", 4, FitMode.FIT);
        Theme saved = themes.save(new Theme(null, "Video Fit", false, videoFit));
        assertEquals(FitMode.FIT, themes.resolve(saved.getId()).getStyle().backgroundFit());
        assertEquals(FitMode.FILL, t.getStyle().backgroundFit(), "existing themes default to Fill");

        StyleOverrides diff = StyleOverrides.diff(base(), base().withBackgroundFit(FitMode.FIT));
        assertEquals(FitMode.FIT, diff.backgroundFit);
        assertEquals(FitMode.FIT, diff.applyTo(base()).backgroundFit());
    }

    @Test
    void videoFormatsAreNotLimitedToMp4() {
        for (String ext : new String[]{"mp4", "mov", "mkv", "avi", "webm", "mpeg", "mpg", "m4v", "wmv", "ts", "mts", "m2ts", "flv"}) {
            assertEquals(MediaKind.VIDEO, MediaKind.of(Path.of("clip." + ext.toUpperCase())).orElseThrow(), ext);
        }
        assertEquals(MediaKind.IMAGE, MediaKind.of(Path.of("photo.webp")).orElseThrow());
        Song song = new Song();
        song.setBackground("media/backgrounds/worship-loop.mkv");
        assertEquals(BackgroundType.VIDEO, StyleOverrides.forSong(song).backgroundType);
        song.setBackground("media/backgrounds/sky.jpg");
        assertEquals(BackgroundType.IMAGE, StyleOverrides.forSong(song).backgroundType);
    }

    @Test
    void webpImagesDecode() throws Exception {
        // 1x1 lossless WebP
        byte[] webp = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
        Path file = Files.write(tempDir.resolve("tiny.webp"), webp);
        BufferedImage img = ImageIO.read(file.toFile());
        assertNotNull(img, "WebP reader is installed");
        assertEquals(1, img.getWidth());
    }

    @Test
    void videoCheckRejectsMissingAndEmptyFilesWithAFriendlyMessage() throws Exception {
        MediaProbe.Result missing = MediaProbe.probeVideo(tempDir.resolve("gone.mov"));
        assertFalse(missing.isPlayable());
        assertTrue(missing.message().contains("not found"));
        MediaProbe.Result empty = MediaProbe.probeVideo(Files.createFile(tempDir.resolve("empty.mkv")));
        assertEquals(MediaProbe.Status.UNPLAYABLE, empty.status());
        // a real file without VLC on the build machine cannot be verified, which is reported, not thrown
        MediaProbe.Result junk = MediaProbe.probeVideo(Files.writeString(tempDir.resolve("junk.avi"), "not a video"));
        assertFalse(junk.isPlayable());
        assertNotNull(junk.message());
    }
}
