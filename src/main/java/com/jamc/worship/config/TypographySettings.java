package com.jamc.worship.config;

import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.FontWeight;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.TextAlign;
import com.jamc.worship.model.TextPosition;
import com.jamc.worship.model.TextTransform;
import com.jamc.worship.model.Typography;
import com.jamc.worship.render.Fonts;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global typography profiles, one per {@link ContentKind}, saved in settings.properties
 * ({@code lyrics.fontFamily}, {@code lyrics.textTransform}, {@code bible.fontSize}...).
 *
 * <p>When a profile is switched on ("Use global lyrics typography") every song, verse or slide
 * of that kind uses it, unless its theme is marked to override the global typography. Song and
 * slide overrides still win over both. The resolution order is: slide override, song override,
 * theme override, global profile.
 *
 * <p>Listeners run on the thread that changed the setting (the Swing thread for the Settings page).
 */
public final class TypographySettings {

    /** Action cards show lyrics with the presentation letter case (otherwise as typed, easier to read). */
    public static final String KEY_ACTION_CARDS_CASE = "ui.actionCards.applyTextCase";

    private final AppConfig config;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public TypographySettings(AppConfig config) {
        this.config = config;
    }

    /** First-run profile; the font is Montserrat when installed, else Segoe UI. */
    public static Typography defaults(ContentKind kind) {
        return Typography.defaults(kind, Fonts.preferred("Montserrat", "Segoe UI"));
    }

    /** Song lyrics use their global profile by default; other content keeps its theme's look until switched on. */
    public boolean isGlobal(ContentKind kind) {
        return config.getBoolean(key(kind, "useGlobal"), kind == ContentKind.LYRICS);
    }

    public void setGlobal(ContentKind kind, boolean on) {
        config.set(key(kind, "useGlobal"), String.valueOf(on));
        saveAndFire();
    }

    public Typography get(ContentKind kind) {
        Typography d = defaults(kind);
        return new Typography(
                config.get(key(kind, "fontFamily"), d.fontFamily()),
                FontWeight.parse(config.get(key(kind, "fontStyle"), null), d.fontWeight()),
                config.getBoolean(key(kind, "italic"), d.italic()),
                parseInt(config.get(key(kind, "fontSize"), null), d.fontSize()),
                TextTransform.parse(config.get(key(kind, "textTransform"), null), d.textTransform()),
                config.get(key(kind, "textColor"), d.fontColor()),
                TextAlign.parse(config.get(key(kind, "alignment"), null), d.align()),
                TextPosition.parse(config.get(key(kind, "verticalPosition"), null), d.position()),
                parseDouble(config.get(key(kind, "lineSpacing"), null), d.lineSpacing()),
                config.getBoolean(key(kind, "shadowEnabled"), d.shadow()),
                parseDouble(config.get(key(kind, "shadowStrength"), null), d.shadowStrength()),
                config.getBoolean(key(kind, "outlineEnabled"), d.outline()),
                config.get(key(kind, "outlineColor"), d.outlineColor()),
                parseInt(config.get(key(kind, "outlineSize"), null), d.outlineWidth()));
    }

    public void set(ContentKind kind, Typography t) {
        config.set(key(kind, "fontFamily"), t.fontFamily());
        config.set(key(kind, "fontStyle"), t.fontWeight().name());
        config.set(key(kind, "italic"), String.valueOf(t.italic()));
        config.set(key(kind, "fontSize"), String.valueOf(t.fontSize()));
        config.set(key(kind, "textTransform"), t.textTransform().name());
        config.set(key(kind, "textColor"), t.fontColor());
        config.set(key(kind, "alignment"), t.align().name());
        config.set(key(kind, "verticalPosition"), t.position().name());
        config.set(key(kind, "lineSpacing"), String.format(Locale.ROOT, "%.2f", t.lineSpacing()));
        config.set(key(kind, "shadowEnabled"), String.valueOf(t.shadow()));
        config.set(key(kind, "shadowStrength"), String.format(Locale.ROOT, "%.2f", t.shadowStrength()));
        config.set(key(kind, "outlineEnabled"), String.valueOf(t.outline()));
        config.set(key(kind, "outlineColor"), t.outlineColor());
        config.set(key(kind, "outlineSize"), String.valueOf(t.outlineWidth()));
        saveAndFire();
    }

    /** Puts the profile back to its first-run values (the Use Global switch is left as it is). */
    public void restoreDefaults(ContentKind kind) {
        set(kind, defaults(kind));
    }

    /** The theme's look with this kind's global typography laid over it, when that profile is on and the theme does not override it. */
    public SlideStyle apply(ContentKind kind, SlideStyle themeStyle, boolean themeOverrides) {
        return themeOverrides || !isGlobal(kind) ? themeStyle : get(kind).applyTo(themeStyle);
    }

    public boolean actionCardsUseCase() {
        return config.getBoolean(KEY_ACTION_CARDS_CASE, false);
    }

    public void setActionCardsUseCase(boolean on) {
        config.set(KEY_ACTION_CARDS_CASE, String.valueOf(on));
        saveAndFire();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void saveAndFire() {
        config.save();
        listeners.forEach(Runnable::run);
    }

    private static String key(ContentKind kind, String name) {
        return kind.key() + "." + name;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return s == null ? def : Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
