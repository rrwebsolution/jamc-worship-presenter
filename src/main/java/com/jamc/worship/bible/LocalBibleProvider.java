package com.jamc.worship.bible;

import com.jamc.worship.repository.BibleRepository;

import java.util.List;
import java.util.Optional;

/**
 * Text stored in the local database: the bundled public-domain KJV and any translation the church
 * imported from a file it is licensed to use. Works offline and supports search.
 */
public final class LocalBibleProvider implements BibleProvider {

    private final BibleRepository repository;

    public LocalBibleProvider(BibleRepository repository) {
        this.repository = repository;
    }

    @Override
    public String problem(BibleVersion version) {
        return translation(version).isPresent() ? null
                : version.abbreviation() + " has not been imported. Import a licensed " + version.abbreviation()
                + " Bible file in Bible Versions.";
    }

    @Override
    public List<Verse> chapter(BibleVersion version, int book, int chapter) {
        return translation(version).map(t -> repository.verses(t.id(), book, chapter, 1, Integer.MAX_VALUE).stream()
                .map(v -> new Verse(v.book(), v.chapter(), v.verse(), v.text())).toList()).orElse(List.of());
    }

    @Override
    public List<Verse> search(BibleVersion version, String words, int limit) {
        return translation(version).map(t -> repository.search(t.id(), words, limit).stream()
                .map(v -> new Verse(v.book(), v.chapter(), v.verse(), v.text())).toList()).orElse(List.of());
    }

    @Override
    public boolean canSearch() {
        return true;
    }

    private Optional<BibleRepository.Translation> translation(BibleVersion version) {
        return repository.find(version.abbreviation());
    }
}
