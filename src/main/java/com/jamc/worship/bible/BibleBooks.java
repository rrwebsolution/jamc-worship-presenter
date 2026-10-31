package com.jamc.worship.bible;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The 66-book Protestant canon with names and common abbreviations. Book numbers (1..66) are the
 * stable key in the database, so translations with different spellings ("I Samuel", "1 Samuel")
 * line up.
 */
public final class BibleBooks {

    public record Book(int number, String name, String abbreviation, boolean oldTestament) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final String[][] DATA = {
            {"Genesis", "Gen", "gen ge gn"}, {"Exodus", "Exod", "exo ex exod"}, {"Leviticus", "Lev", "lev le lv"},
            {"Numbers", "Num", "num nu nm nb"}, {"Deuteronomy", "Deut", "deut dt deu"}, {"Joshua", "Josh", "josh jos jsh"},
            {"Judges", "Judg", "judg jdg jg jdgs"}, {"Ruth", "Ruth", "rth ru"}, {"1 Samuel", "1 Sam", "1sam 1sa 1sm isamuel"},
            {"2 Samuel", "2 Sam", "2sam 2sa 2sm iisamuel"}, {"1 Kings", "1 Kgs", "1kgs 1ki 1kin ikings"},
            {"2 Kings", "2 Kgs", "2kgs 2ki 2kin iikings"}, {"1 Chronicles", "1 Chr", "1chr 1ch 1chron ichronicles"},
            {"2 Chronicles", "2 Chr", "2chr 2ch 2chron iichronicles"}, {"Ezra", "Ezra", "ezr"}, {"Nehemiah", "Neh", "neh ne"},
            {"Esther", "Esth", "esth est es"}, {"Job", "Job", "jb"}, {"Psalms", "Ps", "ps psa psalm pss psm"},
            {"Proverbs", "Prov", "prov pro prv pr"}, {"Ecclesiastes", "Eccl", "eccl ecc ec qoh"},
            {"Song of Solomon", "Song", "song sos ss songofsongs canticles songs"}, {"Isaiah", "Isa", "isa is"},
            {"Jeremiah", "Jer", "jer je jr"}, {"Lamentations", "Lam", "lam la"}, {"Ezekiel", "Ezek", "ezek eze ezk"},
            {"Daniel", "Dan", "dan da dn"}, {"Hosea", "Hos", "hos ho"}, {"Joel", "Joel", "jl"}, {"Amos", "Amos", "am"},
            {"Obadiah", "Obad", "obad ob"}, {"Jonah", "Jonah", "jnh jon"}, {"Micah", "Mic", "mic mc"},
            {"Nahum", "Nah", "nah na"}, {"Habakkuk", "Hab", "hab hb"}, {"Zephaniah", "Zeph", "zeph zep zp"},
            {"Haggai", "Hag", "hag hg"}, {"Zechariah", "Zech", "zech zec zc"}, {"Malachi", "Mal", "mal ml"},
            {"Matthew", "Matt", "matt mt mat"}, {"Mark", "Mark", "mrk mk mr"}, {"Luke", "Luke", "luk lk"},
            {"John", "John", "jn jhn joh"}, {"Acts", "Acts", "act ac"}, {"Romans", "Rom", "rom ro rm"},
            {"1 Corinthians", "1 Cor", "1cor 1co icorinthians"}, {"2 Corinthians", "2 Cor", "2cor 2co iicorinthians"},
            {"Galatians", "Gal", "gal ga"}, {"Ephesians", "Eph", "eph ephes"}, {"Philippians", "Phil", "phil php pp"},
            {"Colossians", "Col", "col co"}, {"1 Thessalonians", "1 Thess", "1thess 1th ithessalonians"},
            {"2 Thessalonians", "2 Thess", "2thess 2th iithessalonians"}, {"1 Timothy", "1 Tim", "1tim 1ti itimothy"},
            {"2 Timothy", "2 Tim", "2tim 2ti iitimothy"}, {"Titus", "Titus", "tit ti"}, {"Philemon", "Phlm", "phlm phm philem"},
            {"Hebrews", "Heb", "heb he"}, {"James", "Jas", "jas jm jam"}, {"1 Peter", "1 Pet", "1pet 1pe 1pt ipeter"},
            {"2 Peter", "2 Pet", "2pet 2pe 2pt iipeter"}, {"1 John", "1 John", "1jn 1jo 1joh ijohn"},
            {"2 John", "2 John", "2jn 2jo 2joh iijohn"}, {"3 John", "3 John", "3jn 3jo 3joh iiijohn"},
            {"Jude", "Jude", "jud jd"}, {"Revelation", "Rev", "rev re rv revelations revelationofjohn apocalypse"},
    };

    /** USFM book codes (used by Bible APIs), in canonical order. Structure only, no text. */
    private static final String[] USFM = (
            "GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM EZK DAN "
                    + "HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH "
                    + "1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split(" ");

    /** Number of chapters per book in the Protestant canon (structure only). */
    private static final int[] CHAPTERS = {50, 40, 27, 36, 34, 24, 21, 4, 31, 24, 22, 25, 29, 36, 10, 13, 10, 42, 150, 31,
            12, 8, 66, 52, 5, 48, 12, 14, 3, 9, 1, 4, 7, 3, 3, 3, 2, 14, 4, 28, 16, 24, 21, 28, 16, 16, 13, 6, 6, 4, 4, 5, 3,
            6, 4, 3, 1, 13, 5, 5, 3, 5, 1, 1, 1, 22};

    private static final List<Book> BOOKS;
    private static final Map<String, Integer> LOOKUP = new HashMap<>();

    static {
        Book[] books = new Book[DATA.length];
        for (int i = 0; i < DATA.length; i++) {
            books[i] = new Book(i + 1, DATA[i][0], DATA[i][1], i < 39);
            LOOKUP.put(key(DATA[i][0]), i + 1);
            LOOKUP.put(key(DATA[i][1]), i + 1);
            for (String alias : DATA[i][2].split(" ")) {
                LOOKUP.put(key(alias), i + 1);
            }
        }
        BOOKS = List.of(books);
    }

    private BibleBooks() {
    }

    public static List<Book> all() {
        return BOOKS;
    }

    public static Book get(int number) {
        return BOOKS.get(number - 1);
    }

    /** USFM code such as "JHN" for John (book 43). */
    public static String usfm(int number) {
        return USFM[number - 1];
    }

    /** Chapters in the book, per the standard Protestant canon. */
    public static int chapterCount(int number) {
        return CHAPTERS[number - 1];
    }

    /**
     * Finds a book from a name as written by people or in Bible files: "John", "jn", "1 Cor",
     * "I Corinthians", "First Corinthians", "Psalm", "Revelation of John".
     */
    public static Optional<Book> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String k = key(name);
        Integer n = LOOKUP.get(k);
        if (n == null) {
            // Unique prefix match, e.g. "phili" -> Philippians is ambiguous, "philip" -> Philippians.
            Integer match = null;
            for (Map.Entry<String, Integer> e : LOOKUP.entrySet()) {
                if (k.length() >= 3 && e.getKey().startsWith(k)) {
                    if (match != null && !match.equals(e.getValue())) {
                        return Optional.empty();
                    }
                    match = e.getValue();
                }
            }
            n = match;
        }
        return n == null ? Optional.empty() : Optional.of(get(n));
    }

    /** Normalizes "I Samuel" / "First Samuel" / "1 Samuel" to "1samuel". */
    static String key(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace(".", " ").strip();
        s = s.replaceFirst("^(iii|third|3rd)\\s+", "3").replaceFirst("^(ii|second|2nd)\\s+", "2")
                .replaceFirst("^(i|first|1st)\\s+", "1");
        return s.replaceAll("\\s+", "");
    }
}
