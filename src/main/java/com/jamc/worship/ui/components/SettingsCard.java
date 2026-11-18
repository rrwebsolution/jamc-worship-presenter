package com.jamc.worship.ui.components;

/**
 * A part of a Settings card whose changes wait for the card's SAVE or APPLY button (a draft until then, shown only in
 * the card's own sample). SettingsPanel finds these inside each card and calls them.
 */
interface SettingsCard {

    /** SAVE: keep the changes (Preview and Program are not refreshed yet). */
    void saveCard();

    /** APPLY: keep the changes and use them on the live outputs too (the card then refreshes Preview and Program). */
    default void applyCard() {
        saveCard();
    }

    /** Told by the card when something changed that the generic watch cannot see (e.g. a design clicked). */
    default void onEdited(Runnable edited) {
    }

    /** Puts the controls back to what is saved (e.g. when Settings opens again), dropping an unsaved draft. */
    default void reloadCard() {
    }
}
