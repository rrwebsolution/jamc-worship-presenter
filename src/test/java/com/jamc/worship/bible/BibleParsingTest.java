package com.jamc.worship.bible;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BibleParsingTest {

    @TempDir
    Path dir;

    @Test
    void booksResolveFromCommonSpellings() {
        assertEquals(43, BibleBooks.find("John").orElseThrow().number());
        assertEquals(43, BibleBooks.find("jn").orElseThrow().number());
        assertEquals(46, BibleBooks.find("1 Cor").orElseThrow().number());
        assertEquals(46, BibleBooks.find("I Corinthians").orElseThrow().number());
        assertEquals(46, BibleBooks.find("First Corinthians").orElseThrow().number());
        assertEquals(19, BibleBooks.find("Psalm").orElseThrow().number());
        assertEquals(66, BibleBooks.find("Revelation of John").orElseThrow().number());
        assertEquals(62, BibleBooks.find("I John").orElseThrow().number());
        assertEquals(64, BibleBooks.find("III John").orElseThrow().number());
        assertEquals(22, BibleBooks.find("Song of Solomon").orElseThrow().number());
        assertTrue(BibleBooks.find("Hezekiah").isEmpty());
        assertEquals(66, BibleBooks.all().size());
    }

    @Test
    void referencesParse() {
        var r = ReferenceParser.parse("John 3:16").orElseThrow();
        assertEquals(43, r.book());
        assertEquals(3, r.chapter());
        assertEquals(16, r.fromVerse());
        assertEquals(16, r.toVerse());

        var range = ReferenceParser.parse("1 Cor 13:4-7").orElseThrow();
        assertEquals(46, range.book());
        assertEquals(4, range.fromVerse());
        assertEquals(7, range.toVerse());

        var chapter = ReferenceParser.parse("Ps 23").orElseThrow();
        assertEquals(19, chapter.book());
        assertTrue(chapter.wholeChapter());

        assertTrue(ReferenceParser.parse("for God so loved").isEmpty(), "plain words are a search, not a reference");
        assertTrue(ReferenceParser.parse("John 3:18-16").isEmpty());
        assertTrue(ReferenceParser.parse("").isEmpty());
    }

    @Test
    void passageReferenceFormatting() {
        assertEquals("John 3:16-18 (KJV)", new BiblePassage("KJV", 43, 3, 16, 18).referenceWithTranslation());
        assertEquals("Psalms 23:1", new BiblePassage("KJV", 19, 23, 1, 1).reference());
    }

    @Test
    void importsScrollmapperJsonAndCleansText() throws Exception {
        String json = """
                {"translation":"TST: Test Translation","books":[{"name":"I John","chapters":[{"chapter":1,"verses":[
                {"verse":1,"text":"That [which] was {H123} from the beginning"}"""
                + ",{\"verse\":2,\"text\":\"b\"}".repeat(1)
                + """
                ]}]}, {"name":"Genesis","chapters":[{"chapter":1,"verses":[
                {"verse":1,"text":"a"},{"verse":2,"text":"b"},{"verse":3,"text":"c"},{"verse":4,"text":"d"},
                {"verse":5,"text":"e"},{"verse":6,"text":"f"},{"verse":7,"text":"g"},{"verse":8,"text":"h"},{"verse":9,"text":"i"}]}]}]}
                """;
        var bible = BibleImporter.readScrollmapperJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "X");
        assertEquals("TST", bible.code());
        assertEquals("Test Translation", bible.name());
        assertEquals(11, bible.verses().size());
        var first = bible.verses().getFirst();
        assertEquals(62, first.book());
        assertEquals("That which was from the beginning", first.text());
    }

    @Test
    void importsDelimitedAndZefaniaFiles() throws Exception {
        StringBuilder tsv = new StringBuilder("Book\tChapter\tVerse\tText\n");
        for (int v = 1; v <= 12; v++) {
            tsv.append("John\t3\t").append(v).append("\tVerse ").append(v).append('\n');
        }
        Path tsvFile = Files.writeString(dir.resolve("myb.tsv"), tsv.toString());
        var t = BibleImporter.read(tsvFile);
        assertEquals("MYB", t.code());
        assertEquals(12, t.verses().size());
        assertEquals(43, t.verses().getFirst().book());

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><XMLBIBLE biblename=\"Zef Test\"><INFORMATION>"
                + "<identifier>ZEF</identifier><language>ENG</language></INFORMATION><BIBLEBOOK bnumber=\"19\" bname=\"Psalms\">"
                + "<CHAPTER cnumber=\"23\">");
        for (int v = 1; v <= 11; v++) {
            xml.append("<VERS vnumber=\"").append(v).append("\">Psalm verse ").append(v).append("</VERS>");
        }
        xml.append("</CHAPTER></BIBLEBOOK></XMLBIBLE>");
        Path xmlFile = Files.writeString(dir.resolve("zef.xml"), xml.toString());
        var z = BibleImporter.read(xmlFile);
        assertEquals("ZEF", z.code());
        assertEquals("Zef Test", z.name());
        assertEquals(11, z.verses().size());
        assertEquals(19, z.verses().getFirst().book());

        Path empty = Files.writeString(dir.resolve("empty.csv"), "nothing here\n");
        assertThrows(java.io.IOException.class, () -> BibleImporter.read(empty));
    }
}
