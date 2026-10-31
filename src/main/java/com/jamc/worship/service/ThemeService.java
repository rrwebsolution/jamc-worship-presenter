package com.jamc.worship.service;

import com.jamc.worship.config.TypographySettings;
import com.jamc.worship.model.ContentKind;
import com.jamc.worship.model.Song;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.model.StyleOverrides;
import com.jamc.worship.model.Theme;
import com.jamc.worship.repository.SettingsRepository;
import com.jamc.worship.repository.ThemeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Theme use cases and style resolution. Blocking: call from background workers.
 *
 * <p>Rules: built-in themes can be edited but not deleted; the default theme cannot be deleted
 * (choose another default first). Songs and decks using a deleted theme fall back to the default.
 */
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);
    public static final int MAX_NAME_LENGTH = 80;

    private final ThemeRepository themes;
    private final SettingsRepository settings;
    private final TypographySettings typography;

    /** Without global typography profiles: every theme shows its own font settings. */
    public ThemeService(ThemeRepository themes, SettingsRepository settings) {
        this(themes, settings, null);
    }

    public ThemeService(ThemeRepository themes, SettingsRepository settings, TypographySettings typography) {
        this.themes = themes;
        this.settings = settings;
        this.typography = typography;
    }

    /** The global typography profiles, or null when this service was made without them. */
    public TypographySettings typography() {
        return typography;
    }

    public List<Theme> list() {
        return themes.findAll();
    }

    public Optional<Theme> load(long id) {
        return themes.findById(id);
    }

    /** The configured default theme, else the first theme, else a built-in fallback that is never stored. */
    public Theme defaultTheme() {
        Optional<Theme> configured = settings.getLong(SettingsRepository.DEFAULT_THEME_ID).flatMap(themes::findById);
        if (configured.isPresent()) {
            return configured.get();
        }
        return themes.findAll().stream().findFirst()
                .orElseGet(() -> new Theme(null, "Default", true, SlideStyle.fallback()));
    }

    public Long defaultThemeId() {
        return defaultTheme().getId();
    }

    public void setDefault(long themeId) {
        Theme theme = themes.findById(themeId).orElseThrow(() -> new ValidationException("That theme no longer exists."));
        settings.set(SettingsRepository.DEFAULT_THEME_ID, String.valueOf(themeId));
        log.info("Default theme set to #{} '{}'", themeId, theme.getName());
    }

    /** The theme with this id, or the default theme when the id is null or the theme was deleted. */
    public Theme resolve(Long themeId) {
        if (themeId != null) {
            Optional<Theme> theme = themes.findById(themeId);
            if (theme.isPresent()) {
                return theme.get();
            }
        }
        return defaultTheme();
    }

    /**
     * A theme's look for one kind of content: the global typography profile of that kind laid over
     * the theme's background, unless the profile is off or the theme overrides it.
     */
    public SlideStyle styleFor(ContentKind kind, Theme theme) {
        return typography == null ? theme.getStyle() : typography.apply(kind, theme.getStyle(), theme.isTypographyOverride());
    }

    /**
     * Song look: its theme (or the default) with the global lyrics typography, then the song's own
     * typography, font and background overrides.
     */
    public SlideStyle styleForSong(Song song) {
        return StyleOverrides.forSong(song).applyTo(styleFor(ContentKind.LYRICS, resolve(song.getThemeId())));
    }

    public SlideStyle styleForDeck(SlideDeck deck) {
        return styleFor(ContentKind.CUSTOM_SLIDES, resolve(deck.getThemeId()));
    }

    public Theme save(Theme theme) {
        String name = theme.getName() == null ? "" : theme.getName().strip();
        if (name.isEmpty()) {
            throw new ValidationException("Please enter a theme name.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("The theme name is too long (maximum " + MAX_NAME_LENGTH + " characters).");
        }
        if (themes.nameExists(name, theme.getId())) {
            throw new ValidationException("A theme named \"" + name + "\" already exists. Please choose another name.");
        }
        theme.setName(name);
        Theme saved = theme.getId() == null ? themes.insert(theme) : themes.update(theme);
        log.info("{} theme #{} '{}'", theme.getId() == null ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    public Theme duplicate(long id) {
        Theme original = themes.findById(id).orElseThrow(() -> new ValidationException("That theme no longer exists."));
        Theme copy = new Theme(null, uniqueName(original.getName() + " (Copy)"), false, original.getStyle());
        copy.setTypographyOverride(original.isTypographyOverride());
        return save(copy);
    }

    /** Returns a name not used by any theme, appending " 2", " 3"... to {@code base} if needed. */
    public String uniqueName(String base) {
        String candidate = base;
        for (int n = 2; themes.nameExists(candidate, null); n++) {
            candidate = base + " " + n;
        }
        return candidate;
    }

    public void delete(long id) {
        Theme theme = themes.findById(id).orElseThrow(() -> new ValidationException("That theme no longer exists."));
        if (theme.isBuiltin()) {
            throw new ValidationException("\"" + theme.getName() + "\" is a built-in theme and cannot be deleted. "
                    + "You can edit it or duplicate it instead.");
        }
        if (theme.getId().equals(defaultThemeId())) {
            throw new ValidationException("\"" + theme.getName() + "\" is the default theme. "
                    + "Choose another default theme before deleting it.");
        }
        themes.delete(id);
        log.info("Deleted theme #{} '{}'", id, theme.getName());
    }

    public int usageCount(long id) {
        return themes.usageCount(id);
    }
}
