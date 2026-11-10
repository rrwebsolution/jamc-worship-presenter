package com.jamc.worship.output;

import com.jamc.worship.model.Scene;
import com.jamc.worship.model.SceneLookup;
import com.jamc.worship.model.Source;
import com.jamc.worship.model.SourceType;
import com.jamc.worship.render.ImageCache;
import com.jamc.worship.render.SlideRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SCENE sources (a scene inside a scene, as in OBS) and GROUP layers on the outputs. */
class NestedSceneTest {

    private static Source color(String hex) {
        Source s = new Source("Color", SourceType.COLOR);
        s.getConfig().color = hex;
        return s;
    }

    private static Source sceneSource(long id, double x, double y, double w, double h) {
        Source s = new Source("Scene", SourceType.SCENE);
        s.getConfig().sceneId = id;
        s.getConfig().x = x;
        s.getConfig().y = y;
        s.getConfig().w = w;
        s.getConfig().h = h;
        return s;
    }

    private static Scene scene(long id, Source... sources) {
        Scene s = new Scene(id, "Scene " + id);
        s.setSources(List.of(sources));
        return s;
    }

    private static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    @AfterEach
    void forget() {
        SceneLookup.install(null);
    }

    @Test
    void anotherSceneShowsInsideTheBox() {
        Scene red = scene(1, color("#FF0000"));
        Scene outer = scene(2, sceneSource(1, 0, 0, 0.5, 0.5));
        SceneLookup.install(Map.of(1L, red, 2L, outer)::get);
        BufferedImage img = new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(null, true, true, false, outer), 320, 180, false);
        assertEquals(0xFF0000, rgb(img, 40, 40), "inside the box: the red scene");
        assertEquals(0x000000, rgb(img, 240, 140), "outside the box: nothing");
    }

    @Test
    void aSceneInsideItselfIsNeverDrawnForever() {
        Scene a = scene(1, color("#00FF00"), sceneSource(2, 0, 0, 1, 1));
        Scene b = scene(2, sceneSource(1, 0, 0, 1, 1)); // 1 shows 2 shows 1 shows …
        SceneLookup.install(Map.of(1L, a, 2L, b)::get);
        BufferedImage img = new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(null, true, true, false, a), 160, 90, false);
        assertEquals(0x00FF00, rgb(img, 80, 45)); // finished, with scene 1's own green
    }

    @Test
    void aGroupDrawsNothingItself() {
        Source group = new Source("Group", SourceType.GROUP);
        group.getConfig().uid = "g";
        Scene s = scene(1, group);
        BufferedImage img = new Compositor(new SlideRenderer(new ImageCache(null)))
                .render(new FrameSpec(null, true, true, false, s), 160, 90, false);
        assertEquals(0x000000, rgb(img, 80, 45));
    }
}
