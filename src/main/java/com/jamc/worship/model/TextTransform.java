package com.jamc.worship.model;

import java.util.Locale;

/**
 * Letter case applied to slide text when it is drawn. The stored lyrics or verses are never
 * changed, so switching back to {@link #ORIGINAL} restores the capitalization as it was typed.
 *
 * <p>Uses {@link Locale#ROOT} so letters such as "i" always become "I" (English, Filipino and
 * Cebuano text); a locale-specific rule like Turkish dotted I would surprise the operator.
 */
public enum TextTransform {
    ORIGINAL("Original", "Aa"),
    UPPERCASE("UPPERCASE", "AA"),
    LOWERCASE("lowercase", "aa"),
    TITLE_CASE("Title Case", "Ab");

    private final String label;
    private final String badge;

    TextTransform(String label, String badge) {
        this.label = label;
        this.badge = badge;
    }

    /** Short marker for compact buttons ("AA" for uppercase). */
    public String badge() {
        return badge;
    }

    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return switch (this) {
            case ORIGINAL -> text;
            case UPPERCASE -> text.toUpperCase(Locale.ROOT);
            case LOWERCASE -> text.toLowerCase(Locale.ROOT);
            case TITLE_CASE -> titleCase(text);
        };
    }

    /** First letter of every word upper case, the rest lower case; "don't" stays "Don't". */
    static String titleCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean wordStart = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                out.appendCodePoint(wordStart ? Character.toTitleCase(cp) : Character.toLowerCase(cp));
                wordStart = false;
            } else {
                out.appendCodePoint(cp);
                // apostrophes and digits stay inside a word
                wordStart = !(Character.isDigit(cp) || cp == '\'' || cp == '’');
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    public static TextTransform parse(String value, TextTransform fallback) {
        try {
            return value == null ? fallback : valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
