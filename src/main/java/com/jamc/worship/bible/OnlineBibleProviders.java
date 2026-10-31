package com.jamc.worship.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Licensed Bible APIs. Each uses the church's own API key and follows the publisher's terms:
 * text is requested when needed and kept only in a short in-memory cache, never written to disk.
 * The copyright notice required by each publisher is shown in Bible Versions.
 *
 * <p>The response parsers are separate static methods so they can be tested without a key.
 */
public final class OnlineBibleProviders {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** "[16]", or "[32-33]" where a translation combines verses (the text is kept under the first). */
    private static final Pattern VERSE_MARK = Pattern.compile("\\[(\\d+)(?:-\\d+)?]");

    private OnlineBibleProviders() {
    }

    /** Looks up API keys (stored in settings) by provider. */
    @FunctionalInterface
    public interface Keys extends Function<BibleVersion.Provider, String> {
    }

    // ------------------------------------------------------------------ ESV API (Crossway)

    /** ESV API v3: https://api.esv.org/v3/passage/text/ with "Authorization: Token &lt;key&gt;". */
    public static BibleProvider esv(Keys keys) {
        return new BibleProvider() {
            @Override
            public String problem(BibleVersion v) {
                return blank(keys.apply(BibleVersion.Provider.ESV_API))
                        ? "Enter your ESV API key (from api.esv.org) in Bible Versions." : null;
            }

            @Override
            public List<Verse> chapter(BibleVersion v, int book, int chapter) throws IOException {
                String q = BibleBooks.get(book).name() + " " + chapter;
                String url = "https://api.esv.org/v3/passage/text/?q=" + enc(q)
                        + "&include-passage-references=false&include-verse-numbers=true&include-first-verse-numbers=true"
                        + "&include-footnotes=false&include-footnote-body=false&include-headings=false"
                        + "&include-short-copyright=false&include-copyright=false&include-selahs=true&indent-poetry=false"
                        + "&indent-paragraphs=0";
                String body = get(url, "Authorization", "Token " + keys.apply(BibleVersion.Provider.ESV_API));
                return parseEsv(body, book, chapter);
            }
        };
    }

    static List<BibleProvider.Verse> parseEsv(String json, int book, int chapter) throws IOException {
        JsonNode passages = JSON.readTree(json).path("passages");
        StringBuilder all = new StringBuilder();
        for (JsonNode p : passages) {
            all.append(p.asText()).append(' ');
        }
        return splitNumbered(all.toString(), book, chapter);
    }

    // ------------------------------------------------------------------ NLT API (Tyndale)

    /** NLT API: https://api.nlt.to/api/passages?ref=John.3&amp;version=NLT&amp;key=&lt;key&gt; (HTML response). */
    public static BibleProvider nlt(Keys keys) {
        return new BibleProvider() {
            @Override
            public String problem(BibleVersion v) {
                return blank(keys.apply(BibleVersion.Provider.NLT_API))
                        ? "Enter your NLT API key (from api.nlt.to) in Bible Versions." : null;
            }

            @Override
            public List<Verse> chapter(BibleVersion v, int book, int chapter) throws IOException {
                String ref = BibleBooks.get(book).name().replace(" ", "") + "." + chapter;
                String version = v.providerConfig() == null || v.providerConfig().isBlank() ? "NLT" : v.providerConfig().strip();
                String url = "https://api.nlt.to/api/passages?ref=" + enc(ref) + "&version=" + enc(version)
                        + "&key=" + enc(keys.apply(BibleVersion.Provider.NLT_API));
                return parseNlt(get(url), book, chapter);
            }
        };
    }

    private static final Pattern NLT_VERSE = Pattern.compile(
            "<verse_export[^>]*\\bvn=\"(\\d+)\"[^>]*>(.*?)</verse_export>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    static List<BibleProvider.Verse> parseNlt(String html, int book, int chapter) {
        List<BibleProvider.Verse> out = new ArrayList<>();
        Matcher m = NLT_VERSE.matcher(html);
        while (m.find()) {
            String inner = m.group(2)
                    .replaceAll("(?is)<span class=\"tn\">.*?</span>", "")      // translator notes
                    .replaceAll("(?is)<a [^>]*class=\"a-tn\"[^>]*>.*?</a>", "")  // note markers
                    .replaceAll("(?is)<span class=\"vn\">.*?</span>", "")      // verse number
                    .replaceAll("(?is)<h\\d[^>]*>.*?</h\\d>", "")              // section headings
                    .replaceAll("<[^>]+>", " ");
            String text = unescape(inner).replaceAll("\\s+", " ").strip();
            if (!text.isEmpty()) {
                out.add(new BibleProvider.Verse(book, chapter, Integer.parseInt(m.group(1)), text));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ API.Bible (American Bible Society)

    /**
     * API.Bible: GET {base}/bibles/{bibleId}/chapters/{USFM}.{chapter}?content-type=text with an
     * "api-key" header. The bible id for each version (e.g. one your key is licensed for) is set in
     * Bible Versions; which versions are available depends on your API.Bible account.
     */
    public static BibleProvider apiBible(Keys keys, String baseUrl) {
        return new BibleProvider() {
            @Override
            public String problem(BibleVersion v) {
                String key = keys.apply(BibleVersion.Provider.API_BIBLE);
                if (blank(key)) {
                    return "Enter your API.Bible key in Bible Versions.";
                }
                if (looksLikeBibleId(key)) {
                    return "The API.Bible key is a Bible ID (like \"…-01\"). Paste the API key from your API.Bible Applications page.";
                }
                return blank(v.providerConfig()) ? "Enter the API.Bible Bible ID for " + v.abbreviation() + " in Bible Versions." : null;
            }

            @Override
            public List<Verse> chapter(BibleVersion v, int book, int chapter) throws IOException {
                String url = baseUrl + "/bibles/" + enc(v.providerConfig().strip()) + "/chapters/" + BibleBooks.usfm(book) + "."
                        + chapter + "?content-type=text&include-notes=false&include-titles=false&include-chapter-numbers=false"
                        + "&include-verse-numbers=true&include-verse-spans=false";
                return parseApiBible(get(url, "api-key", keys.apply(BibleVersion.Provider.API_BIBLE)), book, chapter);
            }
        };
    }

    static List<BibleProvider.Verse> parseApiBible(String json, int book, int chapter) throws IOException {
        return splitNumbered(JSON.readTree(json).path("data").path("content").asText(""), book, chapter);
    }

    // ------------------------------------------------------------------ shared helpers

    /** Splits "[1] text [2] text" into verses. */
    static List<BibleProvider.Verse> splitNumbered(String text, int book, int chapter) {
        List<BibleProvider.Verse> out = new ArrayList<>();
        Matcher m = VERSE_MARK.matcher(text);
        int lastVerse = -1;
        int lastEnd = -1;
        while (m.find()) {
            if (lastVerse > 0) {
                add(out, book, chapter, lastVerse, text.substring(lastEnd, m.start()));
            }
            lastVerse = Integer.parseInt(m.group(1));
            lastEnd = m.end();
        }
        if (lastVerse > 0) {
            add(out, book, chapter, lastVerse, text.substring(lastEnd));
        }
        return out;
    }

    private static void add(List<BibleProvider.Verse> out, int book, int chapter, int verse, String raw) {
        String text = raw.replaceAll("\\s+", " ").strip();
        if (!text.isEmpty()) {
            out.add(new BibleProvider.Verse(book, chapter, verse, text));
        }
    }

    /** "78a9f6124f344018-01": the ID of a Bible on API.Bible, often pasted where the API key belongs. */
    public static boolean looksLikeBibleId(String text) {
        return text != null && text.strip().matches("(?i)[0-9a-f]{16}-\\d{2}");
    }

    private static String get(String url, String... headers) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            req.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                // the service's own reason ("Invalid API key", or a Bible the key is not licensed for) and where to fix it
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
                String reason = m.find() ? m.group(1) : "the API key was not accepted";
                throw new IOException("The Bible API refused the request (HTTP " + res.statusCode() + "): " + reason + ".\n"
                        + (reason.toLowerCase(java.util.Locale.ROOT).contains("key")
                        ? "Copy the full key from your account and paste it in Bible → Manage Versions, then press Test."
                        : "Check that your account is licensed for this version and that its Bible ID is right (Bible → Manage Versions → Set Up)."));
            }
            if (res.statusCode() >= 400) {
                throw new IOException("The Bible API returned HTTP " + res.statusCode() + ".");
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
            throw new IOException("No internet connection to the Bible API. Use an imported version while offline.", e);
        }
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "’")
                .replace("&lsquo;", "‘").replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&mdash;", "—");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
