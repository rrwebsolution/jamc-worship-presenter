package com.jamc.worship.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A composition of layered sources. {@link #getSources()} lists the top layer first (as shown to
 * the operator); the compositor paints from the end of the list towards the start.
 */
public class Scene {

    private Long id;
    private String name;
    private List<Source> sources = new ArrayList<>();
    /** One of the scenes the app installs (Worship, Camera...); it can still be deleted. */
    private boolean builtin;

    public Scene(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Deep copy, used to give Program an immutable snapshot. */
    public Scene copy() {
        Scene s = new Scene(id, name);
        s.sources = new ArrayList<>(sources.stream().map(Source::copy).toList());
        s.builtin = builtin;
        return s;
    }

    public boolean has(SourceType type) {
        return sources.stream().anyMatch(s -> s.isVisible() && s.getType() == type);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Source> getSources() { return sources; }
    public void setSources(List<Source> sources) { this.sources = new ArrayList<>(sources); }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    @Override
    public String toString() {
        return name;
    }
}
