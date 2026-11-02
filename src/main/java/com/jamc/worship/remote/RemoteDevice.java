package com.jamc.worship.remote;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A device the server knows.
 *
 * @param tokenHash SHA-256 of the device's secret token; the token itself is only ever held by the device
 * @param address   the network address it last connected from
 */
public record RemoteDevice(String id, String name, String tokenHash, String address, Set<RemotePermission> permissions,
                           Instant firstSeen, Instant lastSeen) {

    static final int MAX_NAME_LENGTH = 40;

    public RemoteDevice {
        EnumSet<RemotePermission> copy = EnumSet.noneOf(RemotePermission.class);
        copy.addAll(permissions);
        permissions = Collections.unmodifiableSet(copy);
    }

    RemoteDevice withPermissions(Set<RemotePermission> p) {
        return new RemoteDevice(id, name, tokenHash, address, p, firstSeen, lastSeen);
    }

    RemoteDevice seen(String newName, String newAddress, Instant when) {
        return new RemoteDevice(id, newName, tokenHash, newAddress, permissions, firstSeen, when);
    }

    /**
     * Makes a name sent over the network safe to show. It ends up in Swing labels, which treat text starting
     * with {@code <html>} as markup (and would even load its images), so markup characters are removed.
     *
     * @return the cleaned name, or null when nothing is left
     */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.replaceAll("[\\p{Cntrl}<>&\"']", " ").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            return null;
        }
        int end = Math.min(clean.length(), MAX_NAME_LENGTH);
        if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end).strip();
    }

    /** A readable name for a device that did not send one. */
    static String guessName(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase();
        if (ua.contains("iphone")) {
            return "iPhone";
        }
        if (ua.contains("ipad")) {
            return "iPad";
        }
        if (ua.contains("android")) {
            return ua.contains("mobile") ? "Android phone" : "Android tablet";
        }
        if (ua.contains("windows")) {
            return "Windows PC";
        }
        if (ua.contains("macintosh")) {
            return "Mac";
        }
        return "Remote device";
    }
}
