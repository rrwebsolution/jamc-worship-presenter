package com.jamc.worship.model;

import java.time.Instant;

/** A file in the media library. {@code path} is relative to the data directory when the file was copied in. */
public class MediaItem {

    private Long id;
    private String name;
    private MediaKind kind;
    private MediaCategory category;
    private String path;
    private boolean copied;
    private Long fileSize;
    private Long durationMs;
    private Integer width;
    private Integer height;
    private boolean favorite;
    private Instant createdAt;
    private MediaSourceType sourceType = MediaSourceType.LOCAL;
    /** YOUTUBE items: the video id ({@link #path} is {@code youtube:<id>}), channel and thumbnail. */
    private String youtubeVideoId;
    private String channelName;
    private String thumbnailUrl;

    public boolean isYouTube() {
        return sourceType == MediaSourceType.YOUTUBE;
    }

    /** A Pixabay video streamed from Pixabay ({@link #path} is its address; {@link #getYoutubeVideoId} holds the Pixabay id). */
    public boolean isPixabay() {
        return sourceType == MediaSourceType.PIXABAY;
    }

    /** The Pixabay id of a PIXABAY item, or 0. */
    public long pixabayId() {
        if (!isPixabay() || youtubeVideoId == null) {
            return 0;
        }
        try {
            return Long.parseLong(youtubeVideoId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public MediaSourceType getSourceType() { return sourceType; }
    public void setSourceType(MediaSourceType sourceType) { this.sourceType = sourceType == null ? MediaSourceType.LOCAL : sourceType; }
    public String getYoutubeVideoId() { return youtubeVideoId; }
    public void setYoutubeVideoId(String youtubeVideoId) { this.youtubeVideoId = youtubeVideoId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public MediaKind getKind() { return kind; }
    public void setKind(MediaKind kind) { this.kind = kind; }
    public MediaCategory getCategory() { return category; }
    public void setCategory(MediaCategory category) { this.category = category; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public boolean isCopied() { return copied; }
    public void setCopied(boolean copied) { this.copied = copied; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        return o instanceof MediaItem other && id != null && id.equals(other.id);
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
