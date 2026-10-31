package com.jamc.worship.service;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideDeck;
import com.jamc.worship.repository.SlideDeckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** Custom slide deck use cases. Blocking: call from background workers. */
public class SlideDeckService {

    private static final Logger log = LoggerFactory.getLogger(SlideDeckService.class);
    public static final int MAX_NAME_LENGTH = 120;

    private final SlideDeckRepository repository;

    public SlideDeckService(SlideDeckRepository repository) {
        this.repository = repository;
    }

    public List<SlideDeck> search(String text) {
        return repository.findAll(text);
    }

    public Optional<SlideDeck> load(long id) {
        return repository.findById(id);
    }

    /** Creates a deck with one empty slide so the editor always has something to show. */
    public SlideDeck create(String name, Long themeId) {
        SlideDeck deck = new SlideDeck();
        deck.setName(validName(name, null));
        deck.setThemeId(themeId);
        deck.setSlides(List.of(new Slide("Slide 1", "", null)));
        SlideDeck saved = repository.insert(deck);
        log.info("Created slide deck #{} '{}'", saved.getId(), saved.getName());
        return saved;
    }

    public SlideDeck save(SlideDeck deck) {
        deck.setName(validName(deck.getName(), deck.getId()));
        return deck.getId() == null ? repository.insert(deck) : repository.update(deck);
    }

    public SlideDeck duplicate(long id) {
        SlideDeck original = repository.findById(id)
                .orElseThrow(() -> new ValidationException("That slide deck no longer exists."));
        SlideDeck copy = new SlideDeck();
        String name = original.getName() + " (Copy)";
        for (int n = 2; repository.nameExists(name, null); n++) {
            name = original.getName() + " (Copy " + n + ")";
        }
        copy.setName(name);
        copy.setThemeId(original.getThemeId());
        copy.setSlides(original.getSlides().stream().map(Slide::copy).toList());
        SlideDeck saved = repository.insert(copy);
        log.info("Duplicated slide deck #{} as #{}", id, saved.getId());
        return saved;
    }

    public boolean delete(long id) {
        boolean deleted = repository.delete(id);
        log.info("Deleted slide deck #{}: {}", id, deleted);
        return deleted;
    }

    private String validName(String name, Long excludeId) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            throw new ValidationException("Please enter a name for the slide deck.");
        }
        if (n.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("The name is too long (maximum " + MAX_NAME_LENGTH + " characters).");
        }
        if (repository.nameExists(n, excludeId)) {
            throw new ValidationException("A slide deck named \"" + n + "\" already exists.");
        }
        return n;
    }
}
