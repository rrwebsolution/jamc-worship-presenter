package com.jamc.worship.pixabay;

import com.jamc.worship.media.OnlineMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pixabay videos saved before playback chose the ≤1080p version point at Pixabay's "medium" file, which for a 2.7K or
 * 4K upload is 1440p (e.g. 2730x1440 at 60 fps) and made a background stutter. At startup this asks Pixabay once per
 * saved video (answers are cached 24 h by {@link PixabayClient}) which version to play now, and records it with
 * {@link OnlineMedia#playInstead}. The saved addresses themselves are never changed, so scenes, songs, services and the
 * background setting that refer to them keep working.
 */
public final class PixabayLighterVersions {

    private static final Logger log = LoggerFactory.getLogger(PixabayLighterVersions.class);
    /** {@code .../303686_medium.mp4} or {@code .../5-135665794_medium.mp4}: the Pixabay id comes first. */
    private static final Pattern ID = Pattern.compile("/(\\d+)(?:-\\d+)?_(?:large|medium|small|tiny)\\.mp4(?:\\?.*)?$");

    private PixabayLighterVersions() {
    }

    /** The Pixabay id in a Pixabay CDN video address, or 0. */
    static long idOf(String url) {
        if (url == null || !url.contains("pixabay.com/")) {
            return 0;
        }
        Matcher m = ID.matcher(url);
        if (!m.find()) {
            return 0;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Looks up every saved address (library items, the background setting) and records the lighter version to play.
     * Blocking: call it off the Swing thread. Stops quietly without a key, offline, or when Pixabay limits requests.
     *
     * @param addresses stored Pixabay addresses with their ids (0 = read the id from the address)
     * @return how many addresses now play a lighter version
     */
    public static int update(PixabayClient client, Map<String, Long> addresses) {
        Map<Long, String> playback = new LinkedHashMap<>();
        int changed = 0;
        for (Map.Entry<String, Long> e : addresses.entrySet()) {
            String stored = e.getKey();
            long id = e.getValue() != null && e.getValue() > 0 ? e.getValue() : idOf(stored);
            if (!OnlineMedia.isUrl(stored) || id <= 0) {
                continue;
            }
            try {
                if (!playback.containsKey(id)) {
                    playback.put(id, client.byId(id).map(PixabayVideo::playback).map(PixabayVideo.Rendition::url).orElse(null));
                }
            } catch (PixabayClient.PixabayException ex) {
                log.info("Pixabay lighter versions not checked: {}", ex.getMessage());
                break; // no key, rate limit or bad key: the next start tries again
            } catch (Exception ex) {
                log.info("Pixabay lighter versions not checked (offline?): {}", ex.toString());
                break;
            }
            String lighter = playback.get(id);
            if (lighter == null) {
                continue; // Pixabay no longer has it: keep playing what was saved
            }
            OnlineMedia.playInstead(stored, lighter);
            if (!lighter.equals(stored)) {
                changed++;
                log.info("Pixabay video {} plays {} instead of the saved {}", id, tail(lighter), tail(stored));
            }
        }
        return changed;
    }

    /** Convenience for a list of addresses without known ids. */
    public static int updateAddresses(PixabayClient client, Collection<String> addresses) {
        Map<String, Long> m = new LinkedHashMap<>();
        addresses.forEach(a -> m.put(a, 0L));
        return update(client, m);
    }

    private static String tail(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
