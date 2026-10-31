package com.jamc.worship.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A set of custom slides (announcements, sermon points, welcome screens...).
 *
 * <p>List queries return summaries: {@link #getSlideCount()} is filled and {@link #getSlides()} is empty.
 */
public class SlideDeck {

    private Long id;
    private String name = "";
    private Long themeId;
    private List<Slide> slides = new ArrayList<>();
    private int slideCount;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }
    public Long getThemeId() { return themeId; }
    public void setThemeId(Long themeId) { this.themeId = themeId; }
    public List<Slide> getSlides() { return slides; }
    public void setSlides(List<Slide> slides) { this.slides = new ArrayList<>(slides); }
    public int getSlideCount() { return slideCount; }
    public void setSlideCount(int slideCount) { this.slideCount = slideCount; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        return o instanceof SlideDeck other && id != null && id.equals(other.id);
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
