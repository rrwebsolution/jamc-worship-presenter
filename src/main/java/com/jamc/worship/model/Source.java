package com.jamc.worship.model;

import java.util.Objects;

/** One layer of a scene. */
public class Source {

    private Long id;
    private String name;
    private SourceType type;
    private boolean visible = true;
    private SourceConfig config = new SourceConfig();

    public Source(String name, SourceType type) {
        this.name = name;
        this.type = Objects.requireNonNull(type);
    }

    public Source copy() {
        Source s = new Source(name, type);
        s.id = id;
        s.visible = visible;
        s.config = config.copy();
        return s;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public SourceType getType() { return type; }
    public void setType(SourceType type) { this.type = Objects.requireNonNull(type); }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public SourceConfig getConfig() { return config; }
    public void setConfig(SourceConfig config) { this.config = config == null ? new SourceConfig() : config; }

    @Override
    public String toString() {
        return name;
    }
}
