package com.jamc.worship.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A service rundown (or a reusable template of one). */
public class Service {

    private Long id;
    private String name = "";
    private String playlist;
    private LocalDate date;
    private boolean template;
    private String notes;
    private List<ServiceItem> items = new ArrayList<>();
    private int itemCount;
    private Instant updatedAt;

    /** Deep copy without database identity. */
    public Service copyWithoutId() {
        Service s = new Service();
        s.name = name;
        s.playlist = playlist;
        s.date = date;
        s.template = template;
        s.notes = notes;
        s.items = new ArrayList<>(items.stream().map(ServiceItem::copy).toList());
        return s;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }
    public String getPlaylist() { return playlist; }
    public void setPlaylist(String playlist) { this.playlist = playlist; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public boolean isTemplate() { return template; }
    public void setTemplate(boolean template) { this.template = template; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<ServiceItem> getItems() { return items; }
    public void setItems(List<ServiceItem> items) { this.items = new ArrayList<>(items); }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return name;
    }
}
