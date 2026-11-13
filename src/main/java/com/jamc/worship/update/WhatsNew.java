package com.jamc.worship.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * "What's new": the list of new features and fixes of a version, so the operator knows what changed.
 * <p>
 * One source: {@code src/main/resources/whats-new.md}. It is inside the app (shown once after an update,
 * and from Help → What's New) and the release workflow copies it onto the GitHub release between
 * {@link #START} and {@link #END}, where the "Update available" dialog reads it before downloading.
 * <p>
 * The file is simple Markdown: {@code ## heading}, {@code - item}, {@code **bold**}, {@code `code`}, plain lines.
 */
public final class WhatsNew {

    public static final String START = "<!-- whats-new:start -->";
    public static final String END = "<!-- whats-new:end -->";

    /** The last version this computer ran: a different version at startup means it was just updated. */
    public static final String KEY_LAST_RUN_VERSION = "update.lastRunVersion";
    /** The version this computer had before its last update: What's New lists every change since then. */
    public static final String KEY_PREVIOUS_VERSION = "update.previousVersion";

    private WhatsNew() {
    }

    /** The notes inside this app (this version's changes); empty when the file is missing. */
    public static String bundled() {
        try (InputStream in = WhatsNew.class.getResourceAsStream("/whats-new.md")) {
            return in == null ? "" : strip(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    /** The notes part of a GitHub release text; empty for older releases without it. */
    public static String fromReleaseBody(String body) {
        if (body == null) {
            return "";
        }
        int a = body.indexOf(START);
        int b = body.indexOf(END);
        if (a < 0 || b < a) {
            return "";
        }
        return strip(body.substring(a + START.length(), b));
    }

    /** Without the file's title line ("# What's new") and HTML comments for the developer. */
    static String strip(String md) {
        String s = md.replace("\r\n", "\n").replaceAll("(?s)<!--.*?-->", "").strip();
        if (s.startsWith("# ")) {
            int nl = s.indexOf('\n');
            s = nl < 0 ? "" : s.substring(nl + 1).strip();
        }
        return s;
    }

    /** True when the app was updated: a different version than the last start (not a first install). */
    public static boolean justUpdated(String lastRun, String current) {
        return current != null && lastRun != null && !lastRun.isBlank() && !lastRun.equals(current);
    }

    /** The notes as HTML for a Swing text pane. */
    public static String toHtml(String md) {
        StringBuilder out = new StringBuilder();
        boolean inList = false;
        for (String raw : md.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            boolean item = line.startsWith("- ") || line.startsWith("* ");
            if (inList && !item) {
                out.append("</ul>");
                inList = false;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (item) {
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.substring(2).strip())).append("</li>");
            } else if (line.startsWith("# ")) {
                out.append("<h2>").append(inline(line.substring(2).strip())).append("</h2>"); // a version
            } else if (line.startsWith("#")) {
                out.append("<h3>").append(inline(line.replaceFirst("^#+\\s*", ""))).append("</h3>");
            } else {
                out.append("<p>").append(inline(line)).append("</p>");
            }
        }
        if (inList) {
            out.append("</ul>");
        }
        return out.toString();
    }

    private static String inline(String s) {
        String e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        e = e.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        e = e.replaceAll("`(.+?)`", "<code>$1</code>");
        return e;
    }

    /** "0.1.20" for the title; "this version" when unknown. */
    public static String label(String version) {
        return version == null || version.isBlank() ? "this version" : "version " + version.toLowerCase(Locale.ROOT);
    }
}
