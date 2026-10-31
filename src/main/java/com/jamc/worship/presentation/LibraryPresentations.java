package com.jamc.worship.presentation;

import com.jamc.worship.model.Presentation;
import com.jamc.worship.render.PresentationLibraryView;
import com.jamc.worship.service.PresentationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PresentationLibraryView} over the presentation library: looks a presentation up once and
 * keeps its slide picture paths for a few seconds, so a refreshed, renamed or removed presentation is
 * noticed without asking the database on every frame.
 */
public final class LibraryPresentations implements PresentationLibraryView {

    private static final Logger log = LoggerFactory.getLogger(LibraryPresentations.class);
    private static final long TTL_MS = 5000;

    private record Entry(String name, List<String> images, long loadedAt) {
    }

    private final PresentationService service;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public LibraryPresentations(PresentationService service) {
        this.service = service;
    }

    /** Forgets everything (after an import, refresh, rename or removal). */
    public void invalidate() {
        cache.clear();
    }

    @Override
    public String slideImage(long presentationId, int index) {
        Entry e = entry(presentationId);
        return index >= 0 && index < e.images.size() ? e.images.get(index) : null;
    }

    @Override
    public int slideCount(long presentationId) {
        return entry(presentationId).images.size();
    }

    @Override
    public String name(long presentationId) {
        return entry(presentationId).name;
    }

    private Entry entry(long id) {
        Entry e = cache.get(id);
        long now = System.currentTimeMillis();
        if (e == null || now - e.loadedAt > TTL_MS) {
            e = load(id, now);
            cache.put(id, e);
        }
        return e;
    }

    private Entry load(long id, long now) {
        try {
            Presentation p = service.find(id).orElse(null);
            if (p == null) {
                return new Entry(null, List.of(), now);
            }
            List<String> images = service.slides(p).stream()
                    .map(s -> s.image())
                    .filter(Files::isRegularFile)
                    .map(Object::toString)
                    .toList();
            return new Entry(p.name(), images, now);
        } catch (RuntimeException ex) {
            log.warn("Could not look up presentation {}", id, ex);
            return new Entry(null, List.of(), now);
        }
    }
}
