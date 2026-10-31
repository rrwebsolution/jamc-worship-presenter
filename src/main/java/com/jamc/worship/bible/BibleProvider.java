package com.jamc.worship.bible;

import java.io.IOException;
import java.util.List;

/**
 * Supplies the text of a Bible version. Implementations read a local database the church has
 * filled from licensed or public-domain files, or call a licensed API with the church's own key.
 * The presentation engine only ever sees {@link Verse}s, so new sources can be added without
 * touching slides, themes or output.
 */
public interface BibleProvider {

    /** One verse of text. */
    record Verse(int book, int chapter, int verse, String text) {
    }

    /** Null when the version can be used; otherwise a message telling the operator what to set up. */
    String problem(BibleVersion version);

    /** All verses of a chapter, in order. May use the network: call from a background thread. */
    List<Verse> chapter(BibleVersion version, int book, int chapter) throws IOException;

    /** Verses containing all the words, or an empty list if the source cannot search. */
    default List<Verse> search(BibleVersion version, String words, int limit) throws IOException {
        return List.of();
    }

    default boolean canSearch() {
        return false;
    }
}
