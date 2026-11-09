package com.jamc.worship.pixabay;

/**
 * One video from the Pixabay Videos API. Nothing is downloaded: the renditions are Pixabay's own CDN addresses, which
 * the Pixabay API documentation allows to be played directly ("Videos may be embedded directly in your applications").
 *
 * @param durationSec length in seconds (0 when not given)
 * @param user        the creator's Pixabay name (shown as "by user via Pixabay")
 */
public record PixabayVideo(long id, String pageUrl, String tags, int durationSec, String user, long userId,
                           Rendition large, Rendition medium, Rendition small, Rendition tiny) {

    /** One size of the video: its address, size in pixels, approximate bytes and the poster picture of that size. */
    public record Rendition(String url, int width, int height, long size, String thumbnail) {
        public boolean usable() {
            return url != null && !url.isBlank();
        }
    }

    /** For Program and backgrounds: 1080p (medium) when there is one, else 720p, else the biggest available. */
    public Rendition playback() {
        return first(medium, small, large, tiny);
    }

    /** For a hover preview: the smallest file (tiny, about 540p), never the 4K one. */
    public Rendition hover() {
        return first(tiny, small, medium);
    }

    /** The poster picture for a card: a small one first (quick to load). */
    public String poster() {
        for (Rendition r : new Rendition[]{tiny, small, medium, large}) {
            if (r != null && r.thumbnail() != null && !r.thumbnail().isBlank()) {
                return r.thumbnail();
            }
        }
        return null;
    }

    /** A short readable title from the tags ("Clouds, Sky, Heaven"). */
    public String title() {
        if (tags == null || tags.isBlank()) {
            return "Pixabay video " + id;
        }
        StringBuilder b = new StringBuilder();
        String[] parts = tags.split(",");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            String t = parts[i].strip();
            if (t.isEmpty()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        return b.isEmpty() ? "Pixabay video " + id : b.toString();
    }

    /** "by name via Pixabay": the credit shown wherever the video appears. */
    public String credit() {
        return (user == null || user.isBlank() ? "" : "by " + user + " ") + "via Pixabay";
    }

    public String durationText() {
        return durationSec <= 0 ? "" : durationSec / 60 + ":" + String.format("%02d", durationSec % 60);
    }

    /** "1920×1080" of the playback rendition. */
    public String resolutionText() {
        Rendition r = playback();
        return r == null || r.width() <= 0 ? "" : r.width() + "×" + r.height();
    }

    private static Rendition first(Rendition... options) {
        for (Rendition r : options) {
            if (r != null && r.usable()) {
                return r;
            }
        }
        return null;
    }
}
