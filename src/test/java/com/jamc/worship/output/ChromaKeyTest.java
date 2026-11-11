package com.jamc.worship.output;

import com.jamc.worship.model.SourceConfig;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** Chroma key of camera / NDI pictures (green background removed). */
class ChromaKeyTest {

    private static BufferedImage picture() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x00FF00)); // green screen
        g.fillRect(0, 0, 200, 100);
        g.setColor(new Color(0x1FB83A)); // a darker, shadowed green
        g.fillRect(0, 0, 40, 100);
        g.setColor(new Color(0xE0B090)); // skin
        g.fillRect(80, 20, 40, 60);
        g.setColor(Color.WHITE); // white text
        g.fillRect(150, 40, 30, 10);
        g.dispose();
        return img;
    }

    private static SourceConfig keyed() {
        SourceConfig c = new SourceConfig();
        c.chromaKey = Boolean.TRUE;
        return c;
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) >>> 24;
    }

    @Test
    void greenBecomesTransparentAndThePersonStays() {
        BufferedImage out = new ChromaKey().apply(picture(), keyed());
        assertEquals(0, alpha(out, 60, 50), "green screen removed");
        assertEquals(0, alpha(out, 10, 50), "its shadowed part too");
        assertEquals(255, alpha(out, 100, 50), "skin kept");
        assertEquals(255, alpha(out, 160, 45), "white text kept");
    }

    @Test
    void offMeansTheSamePicture() {
        BufferedImage in = picture();
        assertSame(in, new ChromaKey().apply(in, new SourceConfig()));
    }

    @Test
    void blueKey() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 10, 10);
        g.setColor(new Color(0x00FF00));
        g.fillRect(0, 0, 5, 10);
        g.dispose();
        SourceConfig c = keyed();
        c.chromaKeyColor = 0x0000FF;
        BufferedImage out = new ChromaKey().apply(img, c);
        assertEquals(0, alpha(out, 8, 5), "blue removed");
        assertEquals(255, alpha(out, 2, 5), "green kept when the key is blue");
    }

    @Test
    void spillTakesTheGreenTingeOffEdges() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xA0C0A0); // a greyish pixel with a green tinge
        BufferedImage out = new ChromaKey().apply(img, keyed());
        int p = out.getRGB(0, 0);
        assertTrue(((p >> 8) & 0xFF) <= 0xA0, "green no higher than red / blue");
    }

    @Test
    void aStillPictureIsKeyedOnceAndReused() {
        ChromaKey k = new ChromaKey();
        SourceConfig c = keyed();
        BufferedImage in = picture();
        BufferedImage first = k.apply(in, c);
        assertSame(first, k.apply(in, c));
        Graphics2D g = in.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100); // a new picture into the same image (cameras reuse buffers)
        g.dispose();
        BufferedImage second = k.apply(in, c);
        assertEquals(255, alpha(second, 60, 50), "the new picture is keyed again");
    }

    @Test
    void fullHdIsFastEnough() {
        BufferedImage in = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 5; i++) {
            ChromaKey.key(in, out, 0x00FF00, 40, 8, true); // warm up
        }
        long t = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            ChromaKey.key(in, out, 0x00FF00, 40, 8, true);
        }
        double ms = (System.nanoTime() - t) / 1e6 / 10;
        System.out.printf("chroma key 1920x1080: %.1f ms%n", ms);
        assertTrue(ms < 40, "too slow: " + ms + " ms");
    }
}
