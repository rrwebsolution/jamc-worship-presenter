package com.jamc.worship.bible;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A verse range within one chapter, e.g. John 3:16-18. Stored as JSON in service item payloads.
 *
 * @param translation translation code, e.g. "KJV"
 */
public record BiblePassage(String translation, int book, int chapter, int fromVerse, int toVerse) {

    public BiblePassage {
        if (toVerse < fromVerse) {
            toVerse = fromVerse;
        }
    }

    /** "John 3:16" or "John 3:16-18". */
    @JsonIgnore
    public String reference() {
        String base = BibleBooks.get(book).name() + " " + chapter + ":" + fromVerse;
        return toVerse > fromVerse ? base + "-" + toVerse : base;
    }

    /** "John 3:16 (KJV)". */
    @JsonIgnore
    public String referenceWithTranslation() {
        return reference() + (translation == null ? "" : " (" + translation + ")");
    }
}
