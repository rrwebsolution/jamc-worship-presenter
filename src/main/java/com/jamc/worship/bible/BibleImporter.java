package com.jamc.worship.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads Bible text from local files. Supported formats:
 * <ul>
 *   <li>JSON as published by the scrollmapper bible_databases project ({@code .json}, {@code .json.gz})</li>
 *   <li>Zefania XML ({@code .xml})</li>
 *   <li>Delimited text, one verse per line: {@code Book<TAB>Chapter<TAB>Verse<TAB>Text}
 *       (tab, pipe or comma separated; book as name or number 1-66) ({@code .tsv}, {@code .csv}, {@code .txt})</li>
 * </ul>
 * Only import translations you are licensed to use.
 */
public final class BibleImporter {

    public record Verse(int book, int chapter, int verse, String text) {
    }

    /** Parsed translation, ready to store. */
    public record ImportedBible(String code, String name, String language, List<Verse> verses) {
    }

    private BibleImporter() {
    }

    public static ImportedBible read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            String base = name.replaceAll("\\.(gz)$", "");
            if (base.endsWith(".json")) {
                return readScrollmapperJson(in, guessCode(file));
            }
            if (base.endsWith(".xml")) {
                return readZefania(in, guessCode(file));
            }
            return readDelimited(in, guessCode(file));
        }
    }

    public static ImportedBible readScrollmapperJson(InputStream in, String fallbackCode) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        String translation = root.path("translation").asText(fallbackCode);
        String code = fallbackCode;
        String title = translation;
        int colon = translation.indexOf(':');
        if (colon > 0) {
            code = translation.substring(0, colon).strip();
            title = translation.substring(colon + 1).strip();
        }
        title = title.replaceAll("\\s+with Strongs.*$", "");
        List<Verse> verses = new ArrayList<>(32_000);
        for (JsonNode book : root.path("books")) {
            Optional<BibleBooks.Book> b = BibleBooks.find(book.path("name").asText());
            if (b.isEmpty()) {
                continue; // apocrypha or unknown names are skipped
            }
            for (JsonNode chapter : book.path("chapters")) {
                int c = chapter.path("chapter").asInt();
                for (JsonNode verse : chapter.path("verses")) {
                    String text = clean(verse.path("text").asText());
                    if (!text.isEmpty()) {
                        verses.add(new Verse(b.get().number(), c, verse.path("verse").asInt(), text));
                    }
                }
            }
        }
        return validated(new ImportedBible(code, title, "English", verses));
    }

    public static ImportedBible readZefania(InputStream in, String fallbackCode) throws IOException {
        List<Verse> verses = new ArrayList<>(32_000);
        String title = fallbackCode;
        String code = fallbackCode;
        String language = null;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(in);
            int book = 0;
            int chapter = 0;
            String element = "";
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    element = r.getLocalName().toUpperCase(Locale.ROOT);
                    switch (element) {
                        case "XMLBIBLE" -> {
                            String bn = r.getAttributeValue(null, "biblename");
                            if (bn != null && !bn.isBlank()) {
                                title = bn;
                            }
                        }
                        case "BIBLEBOOK" -> {
                            String bnumber = r.getAttributeValue(null, "bnumber");
                            String bname = r.getAttributeValue(null, "bname");
                            book = parseBook(bnumber, bname);
                        }
                        case "CHAPTER" -> chapter = parseInt(r.getAttributeValue(null, "cnumber"));
                        case "VERS" -> {
                            int v = parseInt(r.getAttributeValue(null, "vnumber"));
                            String text = clean(r.getElementText());
                            if (book > 0 && chapter > 0 && v > 0 && !text.isEmpty()) {
                                verses.add(new Verse(book, chapter, v, text));
                            }
                        }
                        case "IDENTIFIER" -> {
                            String id = r.getElementText().strip();
                            if (!id.isEmpty()) {
                                code = id.toUpperCase(Locale.ROOT);
                            }
                        }
                        case "LANGUAGE" -> language = r.getElementText().strip();
                        default -> {
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Not a valid Zefania XML Bible: " + e.getMessage(), e);
        }
        return validated(new ImportedBible(code, title, language, verses));
    }

    public static ImportedBible readDelimited(InputStream in, String code) throws IOException {
        List<Verse> verses = new ArrayList<>(32_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = split(line);
            if (parts.length < 4) {
                continue;
            }
            int book = parseBook(parts[0], parts[0]);
            int chapter = parseInt(parts[1]);
            int verse = parseInt(parts[2]);
            if (book <= 0 || chapter <= 0 || verse <= 0) {
                if (lineNo == 1) {
                    continue; // header row
                }
                throw new IOException("Line " + lineNo + " is not \"Book, Chapter, Verse, Text\": " + line);
            }
            String text = clean(String.join(",", java.util.Arrays.copyOfRange(parts, 3, parts.length)));
            verses.add(new Verse(book, chapter, verse, text));
        }
        return validated(new ImportedBible(code, code, null, verses));
    }

    /** Removes KJV italics brackets, pilcrows, Strong's numbers and repeated whitespace. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\{[^}]*}", "").replaceAll("<[^>]+>", "").replace("[", "").replace("]", "")
                .replace("¶", "").replaceAll("\\s+", " ").strip();
    }

    private static String[] split(String line) {
        if (line.contains("\t")) {
            return line.split("\t");
        }
        if (line.contains("|")) {
            return line.split("\\|");
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }

    private static int parseBook(String number, String name) {
        int n = parseInt(number);
        if (n >= 1 && n <= 66) {
            return n;
        }
        return BibleBooks.find(name).map(BibleBooks.Book::number).orElse(0);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String guessCode(Path file) {
        String n = file.getFileName().toString();
        int dot = n.indexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).toUpperCase(Locale.ROOT);
    }

    private static ImportedBible validated(ImportedBible bible) throws IOException {
        if (bible.verses().size() < 10) {
            throw new IOException("No Bible verses were found in this file.");
        }
        String code = bible.code() == null || bible.code().isBlank() ? "BIBLE" : bible.code().strip();
        return new ImportedBible(code.length() > 12 ? code.substring(0, 12) : code,
                bible.name() == null || bible.name().isBlank() ? code : bible.name().strip(),
                bible.language(), bible.verses());
    }
}
