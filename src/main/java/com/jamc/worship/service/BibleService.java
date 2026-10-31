package com.jamc.worship.service;

import com.jamc.worship.bible.BibleBooks;
import com.jamc.worship.bible.BibleDisplay;
import com.jamc.worship.bible.BibleImporter;
import com.jamc.worship.bible.BiblePassage;
import com.jamc.worship.bible.BibleProvider;
import com.jamc.worship.bible.BibleSlideBuilder;
import com.jamc.worship.bible.BibleVersion;
import com.jamc.worship.bible.LocalBibleProvider;
import com.jamc.worship.bible.OnlineBibleProviders;
import com.jamc.worship.bible.ReferenceParser;
import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;
import com.jamc.worship.repository.BibleRepository;
import com.jamc.worship.repository.BibleVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Bible versions, lookup, search, import and slide generation. Blocking (online versions use the
 * network): call from background workers.
 *
 * <p>Bible text never lives in the application code. The public-domain KJV ships as a data file
 * and is imported on first use; every other version gets its text from a provider the church sets
 * up with its own license (an imported file or a licensed API key).
 */
public class BibleService {

    private static final Logger log = LoggerFactory.getLogger(BibleService.class);
    private static final String BUNDLED_KJV = "/bible/kjv.json.gz";
    private static final int CACHE_CHAPTERS = 60;

    private final BibleRepository repository;
    private final BibleVersionRepository versions;
    private final Map<BibleVersion.Provider, BibleProvider> providers = new EnumMap<>(BibleVersion.Provider.class);
    private final Map<String, List<BibleProvider.Verse>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<BibleProvider.Verse>> eldest) {
                    return size() > CACHE_CHAPTERS;
                }
            });

    public BibleService(BibleRepository repository, BibleVersionRepository versions, OnlineBibleProviders.Keys keys,
                        String apiBibleBaseUrl) {
        this.repository = repository;
        this.versions = versions;
        LocalBibleProvider local = new LocalBibleProvider(repository);
        providers.put(BibleVersion.Provider.PUBLIC_DOMAIN, local);
        providers.put(BibleVersion.Provider.LOCAL_FILE, local);
        providers.put(BibleVersion.Provider.ESV_API, OnlineBibleProviders.esv(keys));
        providers.put(BibleVersion.Provider.NLT_API, OnlineBibleProviders.nlt(keys));
        providers.put(BibleVersion.Provider.API_BIBLE, OnlineBibleProviders.apiBible(keys, apiBibleBaseUrl));
    }

    /** Offline-only service (no API keys); used by tests and tools. */
    public BibleService(BibleRepository repository, BibleVersionRepository versions) {
        this(repository, versions, p -> null, "https://api.scripture.api.bible/v1");
    }

    /** Imports the bundled KJV the first time. Returns all versions. */
    public synchronized List<BibleVersion> ensureInstalled() {
        if (repository.find("KJV").isEmpty()) {
            try (InputStream raw = BibleService.class.getResourceAsStream(BUNDLED_KJV)) {
                if (raw == null) {
                    log.warn("Bundled KJV not found on the classpath");
                } else {
                    long start = System.currentTimeMillis();
                    BibleImporter.ImportedBible kjv = BibleImporter.readScrollmapperJson(new GZIPInputStream(raw), "KJV");
                    int count = repository.save(kjv, "Bundled (public domain)");
                    log.info("Installed bundled KJV ({} verses) in {} ms", count, System.currentTimeMillis() - start);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not install the bundled King James Version", e);
            }
        }
        return versions.findAll();
    }

    public List<BibleVersion> versions() {
        return versions.findAll();
    }

    public Optional<BibleVersion> version(String abbreviation) {
        return versions.find(abbreviation);
    }

    /** Null when the version is ready to use; otherwise what the operator needs to set up. */
    public String problem(BibleVersion v) {
        if (v.provider() == BibleVersion.Provider.NONE) {
            return v.abbreviation() + " (" + v.name() + ") is copyrighted and not set up yet. In Bible Versions, import "
                    + "a licensed " + v.abbreviation() + " file or connect a licensed API key.";
        }
        if (!v.enabled()) {
            return v.abbreviation() + " is turned off in Bible Versions.";
        }
        return providers.get(v.provider()).problem(v);
    }

    public boolean isReady(BibleVersion v) {
        return problem(v) == null;
    }

    public boolean canSearch(BibleVersion v) {
        return providers.containsKey(v.provider()) && providers.get(v.provider()).canSearch();
    }

    /** Chapters in a book. Uses the standard canon, so it is the same for every version. */
    public int chapterCount(BibleVersion v, int book) {
        return BibleBooks.chapterCount(book);
    }

    /** All verses of a chapter in this version (cached in memory; online versions are never stored on disk). */
    public List<BibleProvider.Verse> verses(BibleVersion v, int book, int chapter) throws IOException {
        String problem = problem(v);
        if (problem != null) {
            throw new ValidationException(problem);
        }
        String key = v.abbreviation() + ":" + v.provider() + ":" + book + ":" + chapter;
        List<BibleProvider.Verse> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<BibleProvider.Verse> loaded = List.copyOf(providers.get(v.provider()).chapter(v, book, chapter));
        if (!loaded.isEmpty()) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    public List<BibleProvider.Verse> search(BibleVersion v, String text) throws IOException {
        if (text == null || text.isBlank() || !canSearch(v) || !isReady(v)) {
            return List.of();
        }
        return providers.get(v.provider()).search(v, text, 200);
    }

    /** Resolves a typed reference ("John 3:16-18", "Ps 23") against the version's text. */
    public Optional<BiblePassage> resolve(BibleVersion v, String reference) throws IOException {
        Optional<ReferenceParser.Reference> ref = ReferenceParser.parse(reference);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        ReferenceParser.Reference r = ref.get();
        if (r.chapter() > BibleBooks.chapterCount(r.book())) {
            return Optional.empty();
        }
        List<BibleProvider.Verse> chapter = verses(v, r.book(), r.chapter());
        int last = chapter.isEmpty() ? 0 : chapter.getLast().verse();
        if (last == 0 || r.fromVerse() > last) {
            return Optional.empty();
        }
        return Optional.of(new BiblePassage(v.abbreviation(), r.book(), r.chapter(), r.fromVerse(), Math.min(r.toVerse(), last)));
    }

    /** Slides for a passage, styled and split according to the display options. */
    public List<Slide> slides(BiblePassage p, BibleDisplay display, SlideStyle style, BibleSlideBuilder.Measurer measurer)
            throws IOException {
        BibleVersion v = versions.find(p.translation())
                .orElseThrow(() -> new ValidationException("The " + p.translation() + " version is not available."));
        List<BibleProvider.Verse> chapter = verses(v, p.book(), p.chapter());
        List<BibleProvider.Verse> selected = chapter.stream()
                .filter(x -> x.verse() >= p.fromVerse() && x.verse() <= p.toVerse()).toList();
        return new BibleSlideBuilder(display, style, v.abbreviation(), measurer).build(selected);
    }

    /**
     * Imports a Bible file the church is licensed to use and attaches it to a version (existing,
     * e.g. NIV, or new). The version switches to the imported text and is enabled.
     */
    public BibleVersion importFile(Path file, String abbreviation, String name, String language) throws IOException {
        BibleImporter.ImportedBible read = BibleImporter.read(file);
        String code = abbreviation == null || abbreviation.isBlank() ? read.code() : abbreviation.strip().toUpperCase();
        BibleVersion existing = versions.find(code).orElse(null);
        // An existing version (e.g. NIV) keeps its proper name; only new versions take the file's name.
        String finalName = name != null && !name.isBlank() ? name.strip() : existing != null ? existing.name() : read.name();
        String finalLanguage = language != null ? language : existing != null && existing.language() != null
                ? existing.language() : read.language();
        BibleImporter.ImportedBible bible = new BibleImporter.ImportedBible(code, finalName, finalLanguage, read.verses());
        int count = repository.save(bible, file.getFileName().toString());
        BibleVersion v = new BibleVersion(existing == null ? null : existing.id(), code, bible.name(), bible.language(),
                BibleVersion.Provider.LOCAL_FILE, file.getFileName().toString(), true, null,
                existing == null ? 20 : existing.sortOrder());
        clearCache();
        log.info("Imported {} ({} verses) from {}", code, count, file);
        return versions.save(v);
    }

    public BibleVersion saveVersion(BibleVersion v) {
        clearCache();
        return versions.save(v);
    }

    /** Removes a version that was added by the user (and its imported text). Built-in versions are kept. */
    public void deleteVersion(BibleVersion v) {
        if (List.of("KJV", "NLT", "ESV", "NIV", "APSD").contains(v.abbreviation().toUpperCase())) {
            throw new ValidationException(v.abbreviation() + " is a standard version and cannot be removed. Turn it off instead.");
        }
        repository.find(v.abbreviation()).ifPresent(t -> repository.delete(t.id()));
        versions.delete(v.id());
        clearCache();
    }

    public void clearCache() {
        cache.clear();
    }
}
