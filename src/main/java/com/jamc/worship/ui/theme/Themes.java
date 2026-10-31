package com.jamc.worship.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.jamc.worship.config.AppConfig;

import javax.swing.UIManager;

/** Installs and switches the application look and feel. Call on the event dispatch thread. */
public final class Themes {

    private Themes() {
    }

    public static void install(String theme) {
        UIManager.put("ScrollBar.showButtons", false);
        if (AppConfig.THEME_LIGHT.equals(theme)) {
            JamcLightLaf.setup();
        } else {
            JamcDarkLaf.setup();
        }
    }

    /** Switches theme on a running UI with a short cross-fade. */
    public static void switchTo(String theme) {
        FlatAnimatedLafChange.showSnapshot();
        install(theme);
        FlatLaf.updateUI();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
    }

    public static boolean isDark() {
        return FlatLaf.isLafDark();
    }
}
