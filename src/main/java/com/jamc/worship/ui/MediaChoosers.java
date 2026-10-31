package com.jamc.worship.ui;

import com.jamc.worship.model.MediaKind;
import com.jamc.worship.util.FileUtils;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.util.Locale;
import java.util.TreeSet;

/**
 * File chooser filters for media. Video choosers offer the common formats, every format VLC usually
 * plays, and All Files: the extension is only a hint, VLC decides whether a file really plays
 * ({@link com.jamc.worship.media.MediaProbe}).
 */
public final class MediaChoosers {

    private MediaChoosers() {
    }

    /** Video Files (common) · Supported Video Formats (all known) · All Files. */
    public static void videoFilters(JFileChooser chooser) {
        FileNameExtensionFilter common = new FileNameExtensionFilter(
                "Video Files (MP4, MOV, MKV, AVI, WebM, WMV, MPG, M4V, TS, MTS, FLV)",
                MediaKind.COMMON_VIDEO.toArray(String[]::new));
        FileNameExtensionFilter supported = new FileNameExtensionFilter(
                "Supported Video Formats (" + describe(MediaKind.VIDEO) + ")",
                MediaKind.VIDEO.extensions().toArray(String[]::new));
        install(chooser, common, supported);
    }

    /** Image Files (JPG, PNG, WebP, GIF, BMP) · All Files. */
    public static void imageFilters(JFileChooser chooser) {
        install(chooser, new FileNameExtensionFilter("Image Files (JPG, JPEG, PNG, WebP, GIF, BMP)",
                FileUtils.IMAGE_EXTENSIONS.toArray(String[]::new)));
    }

    /** Backgrounds (videos and images) · Video Files · Image Files · All Files. */
    public static void backgroundFilters(JFileChooser chooser) {
        java.util.List<String> both = new java.util.ArrayList<>(MediaKind.COMMON_VIDEO);
        both.addAll(FileUtils.IMAGE_EXTENSIONS);
        install(chooser, new FileNameExtensionFilter("Backgrounds (videos and images)", both.toArray(String[]::new)),
                new FileNameExtensionFilter("Supported Video Formats (" + describe(MediaKind.VIDEO) + ")",
                        MediaKind.VIDEO.extensions().toArray(String[]::new)),
                new FileNameExtensionFilter("Image Files (JPG, JPEG, PNG, WebP, GIF, BMP)",
                        FileUtils.IMAGE_EXTENSIONS.toArray(String[]::new)));
    }

    private static void install(JFileChooser chooser, FileNameExtensionFilter... filters) {
        chooser.setAcceptAllFileFilterUsed(false);
        for (FileNameExtensionFilter f : filters) {
            chooser.addChoosableFileFilter(f);
        }
        chooser.setAcceptAllFileFilterUsed(true); // "All Files" goes last
        chooser.setFileFilter(filters[0]);
    }

    private static String describe(MediaKind kind) {
        return String.join(", ", new TreeSet<>(kind.extensions().stream().map(e -> e.toUpperCase(Locale.ROOT)).toList()));
    }
}
