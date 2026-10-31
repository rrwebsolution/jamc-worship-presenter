package com.jamc.worship.bible;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses typed references: "John 3:16", "jn 3:16-18", "1 Cor 13:4-7", "Psalm 23", "Ps 23:1",
 * "Gen 1". A chapter without verses means the whole chapter (toVerse = Integer.MAX_VALUE).
 */
public final class ReferenceParser {

    /** Parsed reference before it is checked against a translation's verse counts. */
    public record Reference(int book, int chapter, int fromVerse, int toVerse) {
        public boolean wholeChapter() {
            return toVerse == Integer.MAX_VALUE;
        }
    }

    private static final Pattern REF = Pattern.compile(
            "^\\s*((?:[1-3]|i{1,3}|first|second|third)?\\s*[a-z][a-z .]*?)\\s*(\\d+)(?:\\s*[:.,]\\s*(\\d+)(?:\\s*[-–]\\s*(\\d+))?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private ReferenceParser() {
    }

    public static Optional<Reference> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = REF.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<BibleBooks.Book> book = BibleBooks.find(m.group(1));
        if (book.isEmpty()) {
            return Optional.empty();
        }
        int chapter = Integer.parseInt(m.group(2));
        if (chapter < 1) {
            return Optional.empty();
        }
        if (m.group(3) == null) {
            return Optional.of(new Reference(book.get().number(), chapter, 1, Integer.MAX_VALUE));
        }
        int from = Integer.parseInt(m.group(3));
        int to = m.group(4) == null ? from : Integer.parseInt(m.group(4));
        if (from < 1 || to < from) {
            return Optional.empty();
        }
        return Optional.of(new Reference(book.get().number(), chapter, from, to));
    }
}
