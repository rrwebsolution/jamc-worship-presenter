package com.jamc.worship.output;

import com.jamc.worship.config.LyricsTransitionSettings;

import java.util.List;
import java.util.Objects;

/**
 * Where a lyrics card sits in its song, sent along with it to Preview / Program when a lyrics transition other than
 * NONE is chosen: the song's card texts (in the order the strip shows them, repeats included), this card's index,
 * and a copy of the transition settings at the moment it was sent (so changing Settings never changes what is live).
 *
 * @param songId the library song, so a change inside one song can scroll while another song crossfades
 * @param cards  every card's words, in order (immutable)
 * @param index  this card's position in {@code cards}
 */
public record LyricsContext(Long songId, List<String> cards, int index, LyricsTransitionSettings settings) {

    public LyricsContext {
        cards = cards == null ? List.of() : List.copyOf(cards);
        index = cards.isEmpty() ? 0 : Math.clamp(index, 0, cards.size() - 1);
        settings = settings == null ? LyricsTransitionSettings.defaults() : settings;
    }

    public boolean spotify() {
        return settings.mode() == LyricsTransitionSettings.Mode.SPOTIFY;
    }

    /** The same song with the same words: a change between these two cards scrolls instead of crossfading. */
    public boolean sameSong(LyricsContext other) {
        return other != null && songId != null && songId.equals(other.songId) && cards.equals(other.cards)
                && Objects.equals(settings, other.settings);
    }

    /** The text of card {@code i}, or null outside the song. */
    public String card(int i) {
        return i < 0 || i >= cards.size() ? null : cards.get(i);
    }
}
