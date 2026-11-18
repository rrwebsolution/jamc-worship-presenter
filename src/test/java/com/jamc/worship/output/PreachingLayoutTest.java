package com.jamc.worship.output;

import com.jamc.worship.config.AppPaths;
import com.jamc.worship.config.DatabaseConfig;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.LowerThirdLookup;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.DesignedLayers;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.SceneRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in "Preaching" layout (user 2026-11-17) and the designed-layer pieces it is made of. */
class PreachingLayoutTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void forget() {
        LowerThirdLookup.set(null);
        LiveStatus.install(null);
    }

    @Test
    void thePreachingSceneIsAddedWithItsNameTag() throws Exception {
        AppPaths paths = new AppPaths(tempDir.resolve("home"));
        paths.ensureDirectories();
        DatabaseManager db = new DatabaseManager(DatabaseConfig.forPaths(paths));
        db.initialize();
        Scene preaching = new SceneRepository(db).findAll().stream().filter(s -> s.getName().equals("Preaching")).findFirst().orElseThrow();
        List<LowerThird> lts = new LowerThirdRepository(db).findAll();
        LowerThird badge = lts.stream().filter(l -> l.getStyle() == LowerThird.Style.BADGE).findFirst().orElseThrow();
        assertEquals("Preaching Today", badge.getLabel());
        assertEquals(250, badge.getMarginY());
        badge.setWidth(900);
        badge.setTextAlign(LowerThird.TextAlign.RIGHT);
        Source tag = preaching.getSources().stream().filter(s -> s.getType() == SourceType.LOWER_THIRD).findFirst().orElseThrow();
        assertEquals(badge.getId(), tag.getConfig().lowerThirdId);
        assertTrue(preaching.getSources().stream().anyMatch(s -> Boolean.TRUE.equals(s.getConfig().bibleCard)));

        // a saved change to the name tag survives
        badge.setTitle("Ptr. Ricardo Doguiles");
        badge.setMarginY(300);
        LowerThird again = new LowerThirdRepository(db).save(badge);
        assertEquals(300, again.getMarginY());
        assertEquals(900, again.getWidth(), "the width is saved");
        assertEquals(LowerThird.TextAlign.RIGHT, again.getTextAlign(), "the alignment is saved");
        assertEquals(LowerThird.Style.BADGE, again.getStyle());

        LowerThirdLookup.set(List.of(again));
        Slide verse = new Slide("Matthew 6:33", "But seek first the kingdom of God and His righteousness, and all these things will be added to you.", null);
        verse.setBible(true);
        preaching.getSources().stream().filter(s -> Boolean.TRUE.equals(s.getConfig().bibleCard))
                .forEach(s -> s.getConfig().highlightWords = "kingdom of God, His righteousness");
        BufferedImage img = new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(LiveContent.snapshot(verse, null, "Matthew 6:33"), true, true, false, preaching), 1920, 1080, false);
        String out = System.getProperty("preaching.png");
        if (out != null) {
            ImageIO.write(img, "png", Path.of(out).toFile());
        }
        assertNotEquals(0, img.getRGB(150, 300) & 0xFFFFFF, "the Bible card is drawn");
        assertNotEquals(0, img.getRGB(960, 1000) & 0xFFFFFF, "the title banner is drawn");
        assertEquals(0, img.getRGB(1800, 50) & 0xFFFFFF, "no LIVE badge while not streaming");
        LiveStatus.install(() -> true);
        BufferedImage live = new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(null, true, true, false, preaching), 1920, 1080, false);
        assertNotEquals(0, live.getRGB(1800, 55) & 0xFFFFFF, "the LIVE badge while streaming");
    }

    @Test
    void markedWordsAreColoured() {
        List<DesignedLayers.Run> runs = DesignedLayers.runs("Seek *first* the Kingdom of God now", "kingdom of god");
        assertEquals(List.of(new DesignedLayers.Run("Seek ", false), new DesignedLayers.Run("first", true),
                new DesignedLayers.Run(" the ", false), new DesignedLayers.Run("Kingdom of God", true),
                new DesignedLayers.Run(" now", false)), runs);
        assertNotNull(DesignedLayers.runs("plain", null));
    }

    @Test
    void theCardReferenceFollowsBiblePresentation() {
        Slide top = new Slide("John 3:16", "For God so loved the world", "NIV");
        top.setHeading("JOHN 3:16");
        assertEquals("JOHN 3:16 · NIV", Compositor.cardReference(top), "reference above, version below");
        Slide hidden = new Slide("John 3:16", "For God so loved the world", "NIV");
        assertEquals("NIV", Compositor.cardReference(hidden), "reference hidden: version only");
        Slide noVersion = new Slide("John 3:16", "For God so loved the world", null);
        noVersion.setHeading("John 3:16");
        assertEquals("John 3:16", Compositor.cardReference(noVersion), "no capitals, no version");
    }

    @Test
    void theEditorCardUsesTheBibleTypographyFont() {
        Scene scene = new Scene(null, "Card");
        Source bible = new Source("Bible", SourceType.BIBLE);
        bible.getConfig().x = 0.03;
        bible.getConfig().y = 0.15;
        bible.getConfig().w = 0.5;
        bible.getConfig().h = 0.7;
        bible.getConfig().bibleCard = true;
        scene.getSources().add(bible);
        java.util.function.Function<com.jamc.worship.model.SlideStyle, BufferedImage> draw = style -> {
            Compositor.installBibleSampleStyle(() -> style);
            Compositor compositor = new Compositor(new SlideRenderer(new ImageCache(null)));
            compositor.setEditorMode(true);
            return compositor.render(new FrameSpec(null, true, true, false, scene), 960, 540, false);
        };
        com.jamc.worship.model.SlideStyle base = com.jamc.worship.model.SlideStyle.fallback();
        BufferedImage small = draw.apply(new com.jamc.worship.model.Typography("Serif", com.jamc.worship.model.FontWeight.REGULAR, false, 30,
                com.jamc.worship.model.TextTransform.ORIGINAL, "#FFFFFF", com.jamc.worship.model.TextAlign.LEFT,
                com.jamc.worship.model.TextPosition.MIDDLE, 1.2, false, 0, false, "#000000", 1).applyTo(base));
        BufferedImage big = draw.apply(new com.jamc.worship.model.Typography("SansSerif", com.jamc.worship.model.FontWeight.BOLD, true, 70,
                com.jamc.worship.model.TextTransform.UPPERCASE, "#FF0000", com.jamc.worship.model.TextAlign.LEFT,
                com.jamc.worship.model.TextPosition.MIDDLE, 1.2, false, 0, false, "#000000", 1).applyTo(base));
        Compositor.installBibleSampleStyle(null);
        int differ = 0;
        boolean red = false;
        for (int y = 0; y < 540; y += 2) {
            for (int x = 0; x < 960; x += 2) {
                if (small.getRGB(x, y) != big.getRGB(x, y)) {
                    differ++;
                }
                int p = big.getRGB(x, y);
                red |= ((p >> 16) & 0xFF) > 200 && ((p >> 8) & 0xFF) < 60 && (p & 0xFF) < 60;
            }
        }
        assertTrue(differ > 500, "a different Bible Typography changes the card in Edit Scene");
        assertTrue(red, "the card words take the Bible Typography color");
    }
}
