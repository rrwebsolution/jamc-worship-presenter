package com.jamc.worship.bible;

import com.jamc.worship.model.Slide;
import com.jamc.worship.model.SlideStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns verses into presentation slides.
 *
 * <p>With auto-fit, consecutive verses share a slide as long as the text stays at least
 * {@link #MIN_SCALE} of the theme's font size, and a verse that would be too small on its own is
 * split at sentence or clause boundaries across several slides. The reference ("JOHN 3:16") and
 * the version ("NIV") are placed according to {@link BibleDisplay}.
 */
public final class BibleSlideBuilder {

    /** Text must stay at least this fraction of the theme size to count as fitting. */
    public static final float MIN_SCALE = 0.8f;
    private static final int MAX_VERSES_PER_SLIDE = 4;
    /** Several verses share a slide only while their combined text stays readable from the back row. */
    private static final int MAX_PACKED_CHARS = 260;

    /** Reports the font size the layout engine would use for this text. */
    @FunctionalInterface
    public interface Measurer {
        float fontSize(String heading, String body, String caption, SlideStyle style);
    }

    private final BibleDisplay display;
    private final SlideStyle style;
    private final Measurer measurer;
    private final String version;

    public BibleSlideBuilder(BibleDisplay display, SlideStyle style, String versionAbbreviation, Measurer measurer) {
        this.display = display;
        this.style = style;
        this.measurer = measurer;
        this.version = versionAbbreviation;
    }

    public List<Slide> build(List<BibleProvider.Verse> verses) {
        List<Slide> out = new ArrayList<>();
        int i = 0;
        while (i < verses.size()) {
            List<BibleProvider.Verse> chunk = new ArrayList<>(List.of(verses.get(i)));
            int j = i + 1;
            if (display.autoFit()) {
                while (j < verses.size() && chunk.size() < MAX_VERSES_PER_SLIDE) {
                    List<BibleProvider.Verse> candidate = new ArrayList<>(chunk);
                    candidate.add(verses.get(j));
                    if (body(candidate).length() > MAX_PACKED_CHARS || !fits(candidate)) {
                        break;
                    }
                    chunk = candidate;
                    j++;
                }
            }
            i = j;
            if (display.autoFit() && chunk.size() == 1 && !fits(chunk)) {
                out.addAll(split(chunk.getFirst()));
            } else {
                out.add(slide(chunk, body(chunk), null));
            }
        }
        return out;
    }

    private boolean fits(List<BibleProvider.Verse> chunk) {
        String[] hc = headingAndCaption(chunk);
        return measurer.fontSize(hc[0], body(chunk), hc[1], style) >= style.fontSize() * MIN_SCALE;
    }

    /** Splits one long verse into parts that each fit, at sentence, then clause, then word boundaries. */
    private List<Slide> split(BibleProvider.Verse v) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces(v.text())) {
            String candidate = current.isEmpty() ? piece : current + " " + piece;
            if (current.isEmpty() || fitsText(v, candidate)) {
                current.setLength(0);
                current.append(candidate);
            } else {
                parts.add(current.toString());
                current.setLength(0);
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        List<Slide> slides = new ArrayList<>();
        for (int k = 0; k < parts.size(); k++) {
            String suffix = parts.size() > 1 ? " (" + (k + 1) + "/" + parts.size() + ")" : "";
            slides.add(slide(List.of(v), parts.get(k), suffix));
        }
        return slides;
    }

    private boolean fitsText(BibleProvider.Verse v, String text) {
        String[] hc = headingAndCaption(List.of(v));
        return measurer.fontSize(hc[0], text, hc[1], style) >= style.fontSize() * MIN_SCALE;
    }

    /** Sentences, or clauses when a sentence is very long, or groups of words as a last resort. */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?;:])\\s+")) {
            if (sentence.length() <= 140) {
                out.add(sentence);
                continue;
            }
            for (String clause : sentence.split("(?<=,)\\s+")) {
                if (clause.length() <= 140) {
                    out.add(clause);
                    continue;
                }
                String[] words = clause.split("\\s+");
                for (int i = 0; i < words.length; i += 12) {
                    out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, Math.min(words.length, i + 12))));
                }
            }
        }
        return out;
    }

    private Slide slide(List<BibleProvider.Verse> chunk, String body, String labelSuffix) {
        String[] hc = headingAndCaption(chunk);
        Slide s = new Slide(reference(chunk, false) + (labelSuffix == null ? "" : labelSuffix), body, hc[1]);
        s.setHeading(hc[0]);
        return s;
    }

    private String body(List<BibleProvider.Verse> chunk) {
        if (chunk.size() == 1) {
            return chunk.getFirst().text();
        }
        StringBuilder sb = new StringBuilder();
        for (BibleProvider.Verse v : chunk) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(v.verse()).append("  ").append(v.text());
        }
        return sb.toString();
    }

    /** [heading, caption] for the chosen reference position and version option. */
    private String[] headingAndCaption(List<BibleProvider.Verse> chunk) {
        String ref = reference(chunk, display.uppercaseReference());
        String ver = display.showVersion() && version != null ? version : null;
        return BibleDisplay.headingAndCaption(display.referencePosition(), ref, ver);
    }

    private static String reference(List<BibleProvider.Verse> chunk, boolean upper) {
        BibleProvider.Verse first = chunk.getFirst();
        BibleProvider.Verse last = chunk.getLast();
        String ref = BibleBooks.get(first.book()).name() + " " + first.chapter() + ":" + first.verse()
                + (last.verse() != first.verse() ? "-" + last.verse() : "");
        return upper ? ref.toUpperCase(java.util.Locale.ROOT) : ref;
    }
}
