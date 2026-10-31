package com.jamc.worship.model;

import java.util.Objects;

/** A named, reusable slide look. */
public class Theme {

    private Long id;
    private String name;
    private boolean builtin;
    private SlideStyle style;
    /** True when this theme's own font settings win over the global typography profiles. */
    private boolean typographyOverride;

    public Theme(Long id, String name, boolean builtin, SlideStyle style) {
        this.id = id;
        this.name = name;
        this.builtin = builtin;
        this.style = Objects.requireNonNull(style);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public SlideStyle getStyle() { return style; }
    public void setStyle(SlideStyle style) { this.style = Objects.requireNonNull(style); }
    public boolean isTypographyOverride() { return typographyOverride; }
    public void setTypographyOverride(boolean typographyOverride) { this.typographyOverride = typographyOverride; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Theme other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
