package com.jamc.worship.model;

import com.jamc.worship.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleOverridesTest {

    private static final SlideStyle BASE = SlideStyle.fallback();

    @Test
    void diffOfIdenticalStylesIsEmpty() {
        assertTrue(StyleOverrides.diff(BASE, BASE).isEmpty());
    }

    @Test
    void diffThenApplyReproducesEditedStyle() {
        SlideStyle edited = new SlideStyle(BackgroundType.IMAGE, "media/backgrounds/x.jpg", "#000000", 0.4, "Georgia",
                96, false, true, "#FFEE00", TextAlign.LEFT, TextPosition.BOTTOM, 1.5, false, true, "#112233", 7);
        StyleOverrides o = StyleOverrides.diff(BASE, edited);
        assertEquals(edited, o.applyTo(BASE));
    }

    @Test
    void onlyChangedFieldsAreStoredSoThemeChangesStillApply() {
        SlideStyle edited = new SlideStyle(BASE.backgroundType(), BASE.backgroundValue(), BASE.backgroundColor(),
                BASE.overlayOpacity(), BASE.fontFamily(), 40, BASE.bold(), BASE.italic(), BASE.fontColor(),
                BASE.align(), BASE.position(), BASE.lineSpacing(), BASE.shadow(), BASE.outline(),
                BASE.outlineColor(), BASE.outlineWidth());
        StyleOverrides o = StyleOverrides.diff(BASE, edited);
        assertEquals(40, o.fontSize);
        assertNull(o.fontFamily);

        SlideStyle newTheme = new SlideStyle(BackgroundType.COLOR, "#123456", "#123456", 0, "Georgia", 72, true,
                false, "#FFFFFF", TextAlign.CENTER, TextPosition.MIDDLE, 1.1, true, false, "#000000", 4);
        SlideStyle result = o.applyTo(newTheme);
        assertEquals("Georgia", result.fontFamily(), "un-overridden fields follow the new theme");
        assertEquals(40, result.fontSize(), "overridden fields keep the slide's value");
    }

    @Test
    void jsonRoundTripOmitsNullsAndToleratesUnknownFields() {
        StyleOverrides o = new StyleOverrides();
        o.fontSize = 50;
        o.align = TextAlign.RIGHT;
        String json = Json.write(o);
        assertEquals("{\"fontSize\":50,\"align\":\"RIGHT\"}", json);
        StyleOverrides back = Json.readOrNull("{\"fontSize\":50,\"align\":\"RIGHT\",\"futureField\":1}", StyleOverrides.class);
        assertEquals(50, back.fontSize);
        assertEquals(TextAlign.RIGHT, back.align);
        assertNull(Json.readOrNull("not json", StyleOverrides.class));
    }

    @Test
    void slideStyleClampsOutOfRangeValues() {
        SlideStyle s = new SlideStyle(null, null, null, 5, null, 9999, false, false, null, null, null, -1, false,
                false, null, 0);
        assertEquals(SlideStyle.MAX_FONT_SIZE, s.fontSize());
        assertEquals(0.95, s.overlayOpacity());
        assertEquals(1.0, s.lineSpacing());
        assertEquals(1, s.outlineWidth());
        assertEquals(TextAlign.CENTER, s.align());
    }
}
