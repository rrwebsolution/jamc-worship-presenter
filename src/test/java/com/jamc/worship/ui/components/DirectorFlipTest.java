package com.jamc.worship.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectorFlipTest {

    @Test
    void theCameraPictureIsFlippedLeftToRight() {
        BufferedImage src = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, Color.RED.getRGB());
        src.setRGB(1, 0, Color.BLUE.getRGB());
        BufferedImage out = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        DirectorPanel.drawFlippable(g, src, new Rectangle(0, 0, 2, 1), 0, 0, 2, 1, true);
        g.dispose();
        assertEquals(Color.BLUE.getRGB(), out.getRGB(0, 0), "right goes left");
        assertEquals(Color.RED.getRGB(), out.getRGB(1, 0));
    }
}
