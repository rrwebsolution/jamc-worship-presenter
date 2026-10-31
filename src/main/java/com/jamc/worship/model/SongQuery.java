package com.jamc.worship.model;

/**
 * Filter and ordering for song list queries.
 *
 * @param text          free text matched against title, author, tags and lyrics; blank matches everything
 * @param favoritesOnly restrict to favorited songs
 * @param sort          ordering
 */
public record SongQuery(String text, boolean favoritesOnly, Sort sort) {

    public enum Sort {
        TITLE_ASC("Title A–Z"),
        TITLE_DESC("Title Z–A"),
        AUTHOR("Author"),
        RECENTLY_UPDATED("Recently edited"),
        RECENTLY_ADDED("Recently added");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static SongQuery all() {
        return new SongQuery("", false, Sort.TITLE_ASC);
    }
}
